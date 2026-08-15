#!/usr/bin/env pwsh
# gates-toolkit 一键安装脚本
#
# 零参数模式（推荐，在项目根目录运行）：
#   pwsh scripts/setup-gates.ps1
#   自动使用当前 git 仓库根为目标项目，自动检测项目类型与模块。
#
# 参数化模式（可选）：
#   pwsh scripts/setup-gates.ps1 -Target C:/my/project -ProjectType spring-boot
#   pwsh scripts/setup-gates.ps1 -Target C:/my/project -ProjectType multi-module -SqlModule baafoo-server

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$Target,

    [Parameter(Mandatory = $false)]
    [ValidateSet("spring-boot", "multi-module", "auto")]
    [string]$ProjectType = "auto",

    [Parameter(Mandatory = $false)]
    [string]$BackendDir = "backend",

    [Parameter(Mandatory = $false)]
    [string]$SqlModule,

    [Parameter(Mandatory = $false)]
    [string[]]$JavaModules,

    [Parameter(Mandatory = $false)]
    [switch]$InstallHook = $true,

    [Parameter(Mandatory = $false)]
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolkitRoot = Split-Path -Parent $ScriptDir

# 检测当前平台（与 fetch-binaries.ps1 保持一致）
if ($IsWindows -or $env:OS -eq "Windows_NT") {
    $InstallPlatform = "windows-amd64"
    $WanBin = "wan.exe"; $SqlBin = "sqlguard.exe"; $JgBin = "java-guard.exe"
    $WanOut = "wan.exe"; $SqlOut = "sqlguard.exe"; $JgOut = "java-guard.exe"
} elseif ($IsLinux) {
    $arch = uname -m 2>$null
    if ($arch -eq "aarch64" -or $arch -eq "arm64") {
        $InstallPlatform = "linux-arm64"
        $WanBin = "wan-linux-arm64"; $SqlBin = "sqlguard-linux-arm64"; $JgBin = "java-guard-linux-arm64"
    } else {
        $InstallPlatform = "linux-amd64"
        $WanBin = "wan-linux-amd64"; $SqlBin = "sqlguard-linux-amd64"; $JgBin = "java-guard-linux-amd64"
    }
    $WanOut = "wan"; $SqlOut = "sqlguard"; $JgOut = "java-guard"
} elseif ((uname -s 2>$null) -match "Darwin") {
    Write-Error "macOS 暂无预编译二进制（未发布 darwin 产物），请在 Linux/Windows 或 CI 中使用"
    exit 1
} else {
    Write-Error "无法检测平台，请先用 scripts/fetch-binaries.ps1 下载二进制后重试"
    exit 1
}

# PS5 兼容：显式 UTF-8 读取（Get-Content 按 ANSI 读 no-BOM UTF-8 会乱码/吞换行）
function Read-TextFileUtf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

# 无 BOM UTF-8 写入（PS5 的 Set-Content -Encoding UTF8 会写 BOM，wan 解析 YAML/TOML 会失败）
function Write-TextFileUtf8NoBom {
    param(
        [Parameter(Mandatory = $true, Position = 0)]
        [string]$Path,
        [Parameter(Mandatory = $true, Position = 1, ValueFromPipeline = $true)]
        [string]$Content
    )
    process {
        [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding($false)))
    }
}
# 从 versions.toml 读取工具配置版本（简易解析，与 fetch-binaries.ps1 一致）
function Get-ConfigVersion([string]$tool) {
    $vf = Join-Path $ToolkitRoot "versions.toml"
    if (-not (Test-Path $vf)) { return $null }
    $raw = [System.IO.File]::ReadAllText($vf, [System.Text.Encoding]::UTF8)
    $inSection = $false
    foreach ($line in ($raw -split "`n")) {
        $t = $line.Trim()
        if ($t -match '^\[(.+)\]$') { $inSection = ($matches[1].Trim() -eq $tool); continue }
        if ($inSection -and $t -match '^version\s*=\s*"(.*)"') { return $matches[1] }
    }
    return $null
}

# 比较版本号: 返回 -1 (a<b), 0 (a==b), 1 (a>b)，算法与 fetch-binaries.ps1 一致
function Compare-VersionString([string]$a, [string]$b) {
    if (-not $a) { return -1 }
    if (-not $b) { return 1 }
    $aParts = $a.Split('.')
    $bParts = $b.Split('.')
    $maxLen = [Math]::Max($aParts.Count, $bParts.Count)
    for ($i = 0; $i -lt $maxLen; $i++) {
        $avRaw = if ($i -lt $aParts.Count) { $aParts[$i] } else { "0" }
        $bvRaw = if ($i -lt $bParts.Count) { $bParts[$i] } else { "0" }
        $av = 0; $bv = 0
        if ([int]::TryParse($avRaw, [ref]$av) -and [int]::TryParse($bvRaw, [ref]$bv)) {
            if ($av -lt $bv) { return -1 }
            if ($av -gt $bv) { return 1 }
        } else {
            $cmp = [string]::Compare($avRaw, $bvRaw, [System.StringComparison]::OrdinalIgnoreCase)
            if ($cmp -lt 0) { return -1 }
            if ($cmp -gt 0) { return 1 }
        }
    }
    return 0
}

# 计算 toolkit 内容指纹（staleness 检测用，pre-commit hook 用相同算法重算比对）
# 覆盖所有会流入 gates-tools 的输入：versions.toml + templates/** + bin/**/rules/**
# 字节流 = versions.toml 字节 + 按相对路径序排列的(":" + 相对路径 + ":" + 文件字节)
# 注意：与 templates/hooks/pre-commit.template 中的指纹算法必须保持一致
function Get-ToolkitFingerprint {
    $rel = New-Object System.Collections.Generic.List[string]
    $tplRoot = Join-Path $ToolkitRoot "templates"
    if (Test-Path $tplRoot) {
        Get-ChildItem -Path $tplRoot -Recurse -File | ForEach-Object {
            $rel.Add("templates/" + ($_.FullName.Substring($tplRoot.Length + 1)).Replace('\', '/'))
        }
    }
    foreach ($rulesRel in @("bin/sql-guard/rules", "bin/java-guard/rules")) {
        $rulesRoot = Join-Path $ToolkitRoot $rulesRel
        if (Test-Path $rulesRoot) {
            Get-ChildItem -Path $rulesRoot -Recurse -File | ForEach-Object {
                $rel.Add($rulesRel + "/" + ($_.FullName.Substring($rulesRoot.Length + 1)).Replace('\', '/'))
            }
        }
    }
    $rel.Sort([System.StringComparer]::Ordinal)

    $enc = New-Object System.Text.UTF8Encoding($false)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $ms = New-Object System.IO.MemoryStream
    try {
        $vt = Join-Path $ToolkitRoot "versions.toml"
        if (Test-Path $vt) {
            $b = [System.IO.File]::ReadAllBytes($vt); $ms.Write($b, 0, $b.Length)
        }
        foreach ($r in $rel) {
            $b = $enc.GetBytes(":${r}:"); $ms.Write($b, 0, $b.Length)
            $full = Join-Path $ToolkitRoot ($r -replace '/', [System.IO.Path]::DirectorySeparatorChar)
            if (Test-Path $full) {
                $b = [System.IO.File]::ReadAllBytes($full); $ms.Write($b, 0, $b.Length)
            }
        }
        $hash = $sha.ComputeHash($ms.ToArray())
        return ($hash | ForEach-Object { $_.ToString("x2") }) -join ''
    } finally {
        $sha.Dispose(); $ms.Dispose()
    }
}

# 从当前目录向上找 git 仓库根（零参数模式用）
function Find-GitRoot([string]$start) {
    $d = $start
    while ($true) {
        if (Test-Path (Join-Path $d ".git")) { return $d }
        $parent = Split-Path -Parent $d
        if (-not $parent -or $parent -eq $d) { return $null }
        $d = $parent
    }
}

# 是否交互式终端（CI / 重定向输入时不弹提示，直接按默认行为执行）
$Interactive = -not [Console]::IsInputRedirected

# 目标项目：未指定时自动使用当前 git 仓库根（零参数模式）
if (-not $Target) {
    $Target = Find-GitRoot (Get-Location).Path
    if (-not $Target) { $Target = (Get-Location).Path }
    Write-Host "未指定 -Target，自动使用: $Target"
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  gates-toolkit 一键安装" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "工具集: $ToolkitRoot"
Write-Host "目标项目: $Target"
Write-Host ""

# 校验目标
if (-not (Test-Path $Target)) {
    Write-Error "目标项目不存在: $Target"
    exit 1
}

$Target = (Resolve-Path $Target).Path
if ($Target -eq $ToolkitRoot) {
    Write-Error "目标项目不能是工具集自身（$ToolkitRoot）。请先 cd 到目标项目目录再运行。"
    exit 1
}
$GitDir = Join-Path $Target ".git"
if (-not (Test-Path $GitDir)) {
    Write-Warning "目标不是 git 仓库: $Target"
    if (-not $Force -and $Interactive) {
        $ans = Read-Host "继续? (y/N)"
        if ($ans -ne "y") { exit 1 }
    }
}

# 自动检测项目类型
if ($ProjectType -eq "auto") {
    $pom = Join-Path $Target "pom.xml"
    $backendSrc = Join-Path $Target "backend/src/main/java"
    if (Test-Path $backendSrc) {
        $ProjectType = "spring-boot"
        Write-Host "[auto] 检测到 Spring Boot 单模块布局 (backend/src/main/java)"
    } elseif (Test-Path $pom) {
        $ProjectType = "multi-module"
        Write-Host "[auto] 检测到多模块 Maven (根 pom.xml)"
    } else {
        Write-Error "无法自动检测项目类型，请指定 -ProjectType (spring-boot|multi-module)"
        exit 1
    }
}

# 检测参数
if ($ProjectType -eq "multi-module") {
    # 自动猜测 SQL 模块
    if (-not $SqlModule) {
        $candidates = @(Get-ChildItem -Path $Target -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "src/main/resources/mapper") } |
            Select-Object -ExpandProperty Name)
        if ($candidates -and $candidates.Count -gt 0) {
            if ($candidates.Count -gt 1 -and $Interactive) {
                Write-Host "[auto] 发现多个可能的 SQL 模块:"
                for ($i = 0; $i -lt $candidates.Count; $i++) {
                    Write-Host "  [$($i + 1)] $($candidates[$i])"
                }
                $sel = Read-Host "选择序号（默认 1）"
                $idx = 0
                if ([int]::TryParse($sel, [ref]$idx) -and $idx -ge 1 -and $idx -le $candidates.Count) {
                    $idx -= 1
                } else {
                    $idx = 0
                }
                $SqlModule = $candidates[$idx]
            } else {
                $SqlModule = $candidates[0]
            }
            Write-Host "[auto] SQL 模块: $SqlModule"
        } elseif ($Interactive) {
            $SqlModule = Read-Host "未自动检测到 SQL 模块，请输入 SQL/Mapper 所在模块名"
        }
    }

    # 自动猜测 Java 模块
    if (-not $JavaModules) {
        $JavaModules = Get-ChildItem -Path $Target -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "src/main/java") } |
            Select-Object -ExpandProperty Name
        Write-Host "[auto] Java 模块: $($JavaModules -join ', ')"
    }

    if (-not $SqlModule) {
        Write-Error "未找到 SQL/Mapper 所在模块，请指定 -SqlModule"
        exit 1
    }
    if (-not $JavaModules -or $JavaModules.Count -eq 0) {
        if ($Interactive) {
            $raw = Read-Host "未检测到 Java 模块，请输入模块名（多个用逗号分隔）"
            $JavaModules = @($raw -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            Write-Host "[auto] Java 模块: $($JavaModules -join ', ')"
        }
        if (-not $JavaModules -or $JavaModules.Count -eq 0) {
            Write-Error "未找到 Java 模块，请指定 -JavaModules"
            exit 1
        }
    }
}

# 创建 gates-tools 目录
$toolsDir = Join-Path $Target "gates-tools"
if (Test-Path $toolsDir) {
    if (-not $Force -and $Interactive) {
        Write-Warning "目录已存在: $toolsDir"
        $ans = Read-Host "覆盖? (y/N)"
        if ($ans -ne "y") { exit 1 }
    }
    Remove-Item $toolsDir -Recurse -Force
}
New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/sql-guard/bin" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/sql-guard/config/rules/ddl" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/sql-guard/config/rules/dml" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/sql-guard/config/rules/lib" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/java-guard/bin" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/java-guard/java-parser" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/java-guard/rules/rhai" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/wan/bin" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/wan/workflows" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/hooks" -Force | Out-Null
New-Item -ItemType Directory -Path "$toolsDir/commit-message" -Force | Out-Null

# 确保工具二进制存在（必要时从配置 URL 下载）
Write-Host "==> 检查工具二进制"
$fetchScript = Join-Path $ToolkitRoot "scripts/fetch-binaries.ps1"
if (Test-Path $fetchScript) {
    # 检查是否需要下载：二进制缺失，或本地 .version 落后于 versions.toml 配置版本
    $needFetch = $false
    $bins = @(
        "$ToolkitRoot/bin/wan/$WanBin",
        "$ToolkitRoot/bin/sql-guard/$SqlBin",
        "$ToolkitRoot/bin/java-guard/$JgBin",
        "$ToolkitRoot/bin/java-guard/java-parser/java-parser.jar"
    )
    foreach ($b in $bins) {
        if (-not (Test-Path $b)) { $needFetch = $true; break }
    }

    # 版本落后检查：覆盖"管理员升级 toolkit（versions.toml 变更）后，成员本地二进制落后"的场景，
    # 重跑一次 setup 即完成配置+规则+二进制整体升级。
    # 仅在 .version 存在时比较；二进制在而 .version 缺失（如手工交叉编译覆盖）视为未落后，
    # 避免误覆盖手工放置的二进制。
    if (-not $needFetch) {
        foreach ($tool in @("wan", "sql-guard", "java-guard")) {
            $cfgVer = Get-ConfigVersion $tool
            $verFile = Join-Path $ToolkitRoot "bin/$tool/.version"
            if ($cfgVer -and (Test-Path $verFile)) {
                $localVer = [System.IO.File]::ReadAllText($verFile, [System.Text.Encoding]::UTF8).Trim()
                if ($localVer -and ((Compare-VersionString $localVer $cfgVer) -lt 0)) {
                    $needFetch = $true
                    Write-Host "  发现可升级版本: $tool 本地 v$localVer < 配置 v$cfgVer"
                    break
                }
            }
        }
    }

    if ($needFetch) {
        Write-Host "  二进制不完整或版本落后，执行下载..."
        & $fetchScript -Platform $InstallPlatform
        if ($LASTEXITCODE -ne 0) {
            Write-Error "二进制下载失败，请检查 versions.toml 中的 URL 配置"
            exit 1
        }
    } else {
        Write-Host "  二进制已存在且版本匹配配置，跳过下载"
    }
} else {
    Write-Warning "fetch-binaries.ps1 不存在，假设二进制已在 bin/ 中"
}

# 复制工具二进制到目标项目
Write-Host "==> 复制工具二进制"
$binFiles = @(
    @{ Src = "$ToolkitRoot/bin/wan/$WanBin"; Dst = "$toolsDir/wan/bin/$WanOut" },
    @{ Src = "$ToolkitRoot/bin/sql-guard/$SqlBin"; Dst = "$toolsDir/sql-guard/bin/$SqlOut" },
    @{ Src = "$ToolkitRoot/bin/java-guard/$JgBin"; Dst = "$toolsDir/java-guard/bin/$JgOut" },
    @{ Src = "$ToolkitRoot/bin/java-guard/java-parser/java-parser.jar"; Dst = "$toolsDir/java-guard/java-parser/java-parser.jar" }
)
foreach ($bf in $binFiles) {
    if (Test-Path $bf.Src) {
        Copy-Item $bf.Src $bf.Dst -Force
        if ($IsLinux) { & chmod +x $bf.Dst 2>$null }
    } else {
        Write-Error "二进制不存在: $($bf.Src)"
        exit 1
    }
}
# 规则文件（随仓库提交，一定存在）
Copy-Item "$ToolkitRoot/bin/sql-guard/rules/ddl/*.rhai" "$toolsDir/sql-guard/config/rules/ddl/" -Force
Copy-Item "$ToolkitRoot/bin/sql-guard/rules/dml/*.rhai" "$toolsDir/sql-guard/config/rules/dml/" -Force
Copy-Item "$ToolkitRoot/bin/sql-guard/rules/lib/*.rhai" "$toolsDir/sql-guard/config/rules/lib/" -Force
Copy-Item "$ToolkitRoot/bin/java-guard/rules/*.yml" "$toolsDir/java-guard/rules/" -Force
Copy-Item "$ToolkitRoot/bin/java-guard/rules/rhai/*.rhai" "$toolsDir/java-guard/rules/rhai/" -Force

# 渲染 sql-guard 配置
Write-Host "==> 生成 sql-guard 配置"
$sqlTpl = if ($ProjectType -eq "spring-boot") {
    Read-TextFileUtf8 "$ToolkitRoot/templates/sql-guard/sql-guard-spring-boot.toml"
} else {
    $tpl = Read-TextFileUtf8 "$ToolkitRoot/templates/sql-guard/sql-guard-multi-module.toml"
    $tpl.Replace("{{SQL_MODULE}}", $SqlModule)
}
$sqlTpl | Write-TextFileUtf8NoBom "$toolsDir/sql-guard/sqlguard.toml"
Copy-Item "$ToolkitRoot/templates/sql-guard/sqlguard.rules.toml" "$toolsDir/sql-guard/" -Force

# 渲染 java-guard 配置
Write-Host "==> 生成 java-guard 配置"
if ($ProjectType -eq "spring-boot") {
    Copy-Item "$ToolkitRoot/templates/java-guard/java-guard--spring-boot.yml" "$toolsDir/java-guard/java-guard.yml" -Force
} else {
    $modulesList = ($JavaModules -join " ")
    $tpl = Read-TextFileUtf8 "$ToolkitRoot/templates/java-guard/java-guard--multi-module.yml"
    $tpl = $tpl.Replace("{{MODULES_LIST}}", $modulesList)
    $tpl | Write-TextFileUtf8NoBom "$toolsDir/java-guard/java-guard.yml"
}
Copy-Item "$ToolkitRoot/templates/java-guard/gate-config.yml" "$toolsDir/java-guard/" -Force

# 渲染 wan workflow
Write-Host "==> 生成 wan workflow"
if ($ProjectType -eq "spring-boot") {
    $winTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/wan/workflows/pre-commit--spring-boot.yml"
    $winTpl = $winTpl.Replace("{{BACKEND_DIR}}", $BackendDir)
    $winTpl | Write-TextFileUtf8NoBom "$toolsDir/wan/workflows/pre-commit-win.yml"

    $unixTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/wan/workflows/pre-commit--spring-boot-unix.yml"
    $unixTpl = $unixTpl.Replace("{{BACKEND_DIR}}", $BackendDir)
    $unixTpl | Write-TextFileUtf8NoBom "$toolsDir/wan/workflows/pre-commit-unix.yml"
} else {
    $modulesList = ($JavaModules -join " ")
    $modulesArrayPs = ($JavaModules | ForEach-Object { '"' + $_ + '"' }) -join ", "

    $winTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/wan/workflows/pre-commit--multi-module.yml"
    $winTpl = $winTpl.Replace("{{SQL_MODULE}}", $SqlModule)
    $winTpl = $winTpl.Replace("{{MODULES_ARRAY_PS}}", $modulesArrayPs)
    $winTpl | Write-TextFileUtf8NoBom "$toolsDir/wan/workflows/pre-commit-win.yml"

    $unixTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/wan/workflows/pre-commit--multi-module-unix.yml"
    $unixTpl = $unixTpl.Replace("{{SQL_MODULE}}", $SqlModule)
    $unixTpl = $unixTpl.Replace("{{MODULES_LIST}}", $modulesList)
    $unixTpl | Write-TextFileUtf8NoBom "$toolsDir/wan/workflows/pre-commit-unix.yml"

    # CI 专用 workflow（BASE_REF 控制增量/全量，见模板头部注释）
    $ciTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/wan/workflows/ci--multi-module-unix.yml"
    $ciTpl = $ciTpl.Replace("{{SQL_MODULE}}", $SqlModule)
    $ciTpl = $ciTpl.Replace("{{MODULES_LIST}}", $modulesList)
    $ciTpl | Write-TextFileUtf8NoBom "$toolsDir/wan/workflows/ci-unix.yml"
}

# 渲染 hook
Write-Host "==> 生成 pre-commit hook"
$hookTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/hooks/pre-commit.template"
if ($ProjectType -eq "spring-boot") {
    $sqlBlock = @'
echo ""
echo "=== SqlGuard Check ==="
SQLGUARD="$TOOLS_DIR/sql-guard/bin/sqlguard"
SQL_CONFIG="$TOOLS_DIR/sql-guard/sqlguard.toml"
SQL_TARGET="$PROJECT_ROOT/__BACKEND__/src/main/resources"
if [ -f "$SQLGUARD.exe" ]; then SQLGUARD="$SQLGUARD.exe"; fi
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET" || {
  echo "✗ SqlGuard 门禁未通过，提交已被阻止。修复后重试；确认跳过: git commit --no-verify" >&2
  exit 1
}
echo "✓ SqlGuard passed"
'@
    $sqlBlock = $sqlBlock.Replace('__BACKEND__', $BackendDir)

    $javaBlock = @'
echo ""
echo "=== JavaGuard Check ==="
JAVAGUARD="$TOOLS_DIR/java-guard/bin/java-guard"
JAVA_CONFIG="$TOOLS_DIR/java-guard/java-guard.yml"
GATE_CONFIG="$TOOLS_DIR/java-guard/gate-config.yml"
if [ -f "$JAVAGUARD.exe" ]; then JAVAGUARD="$JAVAGUARD.exe"; fi
JAVA_TARGET="$PROJECT_ROOT/__BACKEND__/src/main/java"
[ ! -d "$JAVA_TARGET" ] && { echo "skip: source dir not found"; exit 0; }
"$JAVAGUARD" scan "$JAVA_TARGET" --rules-dir "$TOOLS_DIR/java-guard/rules" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console || {
  echo "✗ JavaGuard 门禁未通过，提交已被阻止。修复后重试；确认跳过: git commit --no-verify" >&2
  exit 1
}
echo "✓ JavaGuard passed"
'@
    $javaBlock = $javaBlock.Replace('__BACKEND__', $BackendDir)

    $hookTpl = $hookTpl.Replace("{{FALLBACK_SQL_BLOCK}}", $sqlBlock)
    $hookTpl = $hookTpl.Replace("{{FALLBACK_JAVA_BLOCK}}", $javaBlock)
} else {
    $sqlBlock = @'
echo ""
echo "=== SqlGuard Check ==="
SQLGUARD="$TOOLS_DIR/sql-guard/bin/sqlguard"
SQL_CONFIG="$TOOLS_DIR/sql-guard/sqlguard.toml"
SQL_TARGET="$PROJECT_ROOT/__SQL_MODULE__/src/main/resources"
if [ -f "$SQLGUARD.exe" ]; then SQLGUARD="$SQLGUARD.exe"; fi
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET" || {
  echo "✗ SqlGuard 门禁未通过，提交已被阻止。修复后重试；确认跳过: git commit --no-verify" >&2
  exit 1
}
echo "✓ SqlGuard passed"
'@
    $sqlBlock = $sqlBlock.Replace('__SQL_MODULE__', $SqlModule)

    # 注意：必须用单引号 here-string + 占位符替换。双引号 here-string 里 \$TOOLS_DIR
    # 会被 PowerShell 当作变量展开（变量名大小写不敏感，命中 $toolsDir = 绝对路径），
    # 生成损坏的 hook 脚本。
    $modulesList = ($JavaModules -join ' ')
    $javaBlock = @'
echo ""
echo "=== JavaGuard Check ==="
JAVAGUARD="$TOOLS_DIR/java-guard/bin/java-guard"
JAVA_CONFIG="$TOOLS_DIR/java-guard/java-guard.yml"
GATE_CONFIG="$TOOLS_DIR/java-guard/gate-config.yml"
if [ -f "$JAVAGUARD.exe" ]; then JAVAGUARD="$JAVAGUARD.exe"; fi
MODULES="__MODULES_LIST__"
for MOD in $MODULES; do
  SRC="$PROJECT_ROOT/$MOD/src/main/java"
  [ ! -d "$SRC" ] && continue
  echo "  scanning $MOD ..."
  "$JAVAGUARD" scan "$SRC" --rules-dir "$TOOLS_DIR/java-guard/rules" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console || {
    echo "✗ JavaGuard 门禁未通过（$MOD），提交已被阻止。修复后重试；确认跳过: git commit --no-verify" >&2
    exit 1
  }
done
echo "✓ JavaGuard passed"
'@
    $javaBlock = $javaBlock.Replace('__MODULES_LIST__', $modulesList)

    $hookTpl = $hookTpl.Replace("{{FALLBACK_SQL_BLOCK}}", $sqlBlock)
    $hookTpl = $hookTpl.Replace("{{FALLBACK_JAVA_BLOCK}}", $javaBlock)
}
$hookTpl | Write-TextFileUtf8NoBom "$toolsDir/hooks/pre-commit"

# 生成 commit 信息模板与校验规则（独立文件，后续可单独编辑）
Write-Host "==> 生成 commit 信息模板与校验规则"
Copy-Item "$ToolkitRoot/templates/commit-message/commit.template" "$toolsDir/commit-message/commit.template" -Force
Copy-Item "$ToolkitRoot/templates/commit-message/commit-msg.config" "$toolsDir/commit-message/commit-msg.config" -Force

# 生成 prepare-commit-msg / commit-msg hook（无占位符，直接按模板渲染）
Write-Host "==> 生成 prepare-commit-msg / commit-msg hook"
$pcmTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/hooks/prepare-commit-msg.template"
$pcmTpl | Write-TextFileUtf8NoBom "$toolsDir/hooks/prepare-commit-msg"
$cmTpl = Read-TextFileUtf8 "$ToolkitRoot/templates/hooks/commit-msg.template"
$cmTpl | Write-TextFileUtf8NoBom "$toolsDir/hooks/commit-msg"

# 生成手动运行脚本（一键触发门禁，无需记长命令）
Write-Host "==> 生成 gatecheck 快捷脚本"
$gatecheckCmd = @'
@echo off
rem gatecheck.cmd - run code gates manually (generated by gates-toolkit)
rem Usage: gates-tools\gatecheck.cmd
rem NOTE: ASCII only on purpose (cmd parses batch files using OEM codepage,
rem       UTF-8 comments before chcp can corrupt line endings)
chcp 65001 >nul
setlocal
cd /d "%~dp0.."
set "WAN=gates-tools\wan\bin\wan.exe"
if not exist "%WAN%" (
  echo [ERROR] wan binary not found: %WAN%
  exit /b 1
)
rem Prefer Windows workflow when pwsh exists, else fall back to bash workflow
rem (same logic as the pre-commit hook)
set "WF=gates-tools\wan\workflows\pre-commit-unix.yml"
where pwsh >nul 2>nul
if not errorlevel 1 set "WF=gates-tools\wan\workflows\pre-commit-win.yml"
"%WAN%" run -C . "%WF%"
exit /b %ERRORLEVEL%
'@
$gatecheckCmd | Write-TextFileUtf8NoBom "$toolsDir/gatecheck.cmd"

$gatecheckSh = @'
#!/bin/sh
# 手动运行代码门禁（由 gates-toolkit 自动生成）
# 用法: bash gates-tools/gatecheck.sh
cd "$(dirname "$0")/.." || exit 1
WAN="gates-tools/wan/bin/wan"
if [ ! -x "$WAN" ] && [ -x "$WAN.exe" ]; then WAN="$WAN.exe"; fi
if [ ! -x "$WAN" ]; then
  echo "[ERROR] wan binary not found: $WAN" >&2
  exit 1
fi
WF="gates-tools/wan/workflows/pre-commit-unix.yml"
if [ -f "gates-tools/wan/workflows/pre-commit-win.yml" ] && command -v pwsh >/dev/null 2>&1; then
  WF="gates-tools/wan/workflows/pre-commit-win.yml"
fi
"$WAN" run -C . "$WF"
'@
$gatecheckSh | Write-TextFileUtf8NoBom "$toolsDir/gatecheck.sh"

# 写入 toolkit 指纹（staleness 检测：pre-commit hook 重算比对，不一致时提示重跑 setup）
Write-Host "==> 写入 toolkit 指纹"
$toolkitFingerprint = Get-ToolkitFingerprint
if ($toolkitFingerprint) {
    $metaContent = "# 由 gates-toolkit setup-gates 生成；pre-commit hook 用于 staleness 检测`n"
    $metaContent += "toolkit_fingerprint=$toolkitFingerprint`n"
    $metaContent += "setup_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n"
    $metaContent | Write-TextFileUtf8NoBom "$toolsDir/.meta"
} else {
    Write-Warning "toolkit 指纹计算失败，跳过 .meta 写入"
}

# 生成 README
Write-Host "==> 生成 gates-tools/README.md"
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

$readme = "# gates-tools/ — 代码门禁工具集`n`n"
$readme += "由 gates-toolkit 自动生成于 $timestamp`n`n"
$readme += "## 项目类型`n$ProjectType`n`n"

if ($ProjectType -eq "spring-boot") {
    $readme += "## 扫描路径`n"
    $readme += "- SQL/Mapper: ``$BackendDir/src/main/resources```n"
    $readme += "- Java: ``$BackendDir/src/main/java```n`n"
} else {
    $modulesStr = (($JavaModules | ForEach-Object { "``$_/src/main/java``" }) -join ", ")
    $readme += "## 扫描路径`n"
    $readme += "- SQL/Mapper: ``$SqlModule/src/main/resources```n"
    $readme += "- Java 模块: $modulesStr`n`n"
}

$readme += "## 使用`n`n"
$readme += "### 本地门禁`n`n"
$readme += "``git commit`` 时自动检查。如需跳过: ``git commit --no-verify```n`n"
$readme += "### 手动运行`n`n"
$readme += "一键触发全部门禁:`n"
$readme += "- Windows: ``gates-tools\gatecheck.cmd``（双击即可）`n"
$readme += "- Linux/macOS: ``bash gates-tools/gatecheck.sh```n`n"
$readme += "等价于以下完整命令（也可单独执行）:`n`n"
$readme += '```powershell' + "`n"
$readme += "# SqlGuard`n"
$readme += "gates-tools/sql-guard/bin/sqlguard.exe check-diff --base HEAD -c gates-tools/sql-guard/sqlguard.toml -f plain $BackendDir/src/main/resources`n`n"
$readme += "# JavaGuard`n"
$readme += "`$env:JAVAGUARD_PARSER_JAR = `"gates-tools/java-guard/java-parser/java-parser.jar`"`n"
$readme += "gates-tools/java-guard/bin/java-guard.exe scan $BackendDir/src/main/java --rules-dir gates-tools/java-guard/rules --config gates-tools/java-guard/java-guard.yml --gate --gate-config gates-tools/java-guard/gate-config.yml --diff HEAD -f console`n`n"
$readme += "# wan 编排`n"
$readme += "gates-tools/wan/bin/wan.exe run pre-commit-win -C .`n"
$readme += "````n`n"
$readme += "### CI 集成`n参考 ``.cnb.yml`` (如已存在则手动添加 code-gate job)。`n"

$readme | Write-TextFileUtf8NoBom "$toolsDir/README.md"

# gates-tools 为生成产物：自动加入目标项目 .gitignore，避免误提交
Write-Host "==> 更新目标项目 .gitignore（忽略生成的 gates-tools/）"
$targetGitignore = Join-Path $Target ".gitignore"
$gatesToolsIgnore = "gates-tools/"
$existingGitignore = ""
if (Test-Path $targetGitignore) { $existingGitignore = Read-TextFileUtf8 $targetGitignore }
if ($existingGitignore -notmatch "(?m)^gates-tools/?$") {
    if ($existingGitignore -and -not $existingGitignore.EndsWith("`n")) { $existingGitignore += "`n" }
    $existingGitignore += "# gates-toolkit 门禁产物（由 setup-gates 生成，不入库）`n$gatesToolsIgnore`n"
    $existingGitignore | Write-TextFileUtf8NoBom $targetGitignore
    Write-Host "    -> $targetGitignore"
} else {
    Write-Host "    已存在，跳过"
}

# hook 是否由本工具生成（依据模板头部 marker 判断，避免误备份/覆盖第三方 hook）
function Test-ToolkitHook([string]$Path) {
    if (-not (Test-Path $Path)) { return $true }
    return (Read-TextFileUtf8 $Path) -match 'Generated by gates-toolkit'
}

# 安装单个 hook：非本工具生成且已存在时，先备份（保留 .bak，冲突时追加时间戳）再覆盖
function Install-HookFile([string]$Name) {
    $src = Join-Path $toolsDir "hooks/$Name"
    $dst = Join-Path $GitDir "hooks/$Name"
    if (Test-Path $dst) {
        if (Test-ToolkitHook $dst) {
            Write-Host "    $Name 已存在（gates-toolkit 生成），直接覆盖"
        } else {
            if (-not $Force -and $Interactive) {
                $ans = Read-Host "    检测到已有 $Name hook（非 gates-toolkit 生成），将备份后覆盖。继续? (y/N)"
                if ($ans -ne "y") { exit 1 }
            }
            $bak = "$dst.bak"
            if (Test-Path $bak) { $bak = "$dst.bak.$(Get-Date -Format 'yyyyMMddHHmmss')" }
            Copy-Item $dst $bak -Force
            Write-Host "    检测到已有 $Name hook（非 gates-toolkit 生成），已备份: $bak"
        }
    }
    Copy-Item $src $dst -Force
    Write-Host "    -> $dst"
}

# 安装 hook
if ($InstallHook -and (Test-Path $GitDir)) {
    # core.hooksPath 检测：已配置指向其它目录时，git 不会执行 .git/hooks/ 下的 hook，安装将不生效
    $hooksPath = (& git config --get core.hooksPath 2>$null)
    if ($hooksPath) {
        Write-Warning "检测到 core.hooksPath=$hooksPath ，git 将只执行该目录下的 hook，.git/hooks/ 安装将不生效。"
        if (-not $Force -and $Interactive) {
            $ans = Read-Host "继续安装到 .git/hooks/ ? (y/N)"
            if ($ans -ne "y") { exit 1 }
        }
    }

    Write-Host "==> 安装 git pre-commit hook"
    Install-HookFile "pre-commit"

    Write-Host "==> 安装 git commit 信息 hooks（prepare-commit-msg / commit-msg）"
    Install-HookFile "prepare-commit-msg"
    Install-HookFile "commit-msg"
}

# 配置 commit.template：VSCode 提交输入框 / IntelliJ 提交对话框据此预填模板
# （IDE 不执行 prepare-commit-msg，只有 git 原生 commit.template 才能在 IDE 输入框预填；
#   用绝对路径——各成员仓库克隆位置不同，相对路径会因 git 执行目录而失效）
if (Test-Path $GitDir) {
    $templateFile = (Join-Path $toolsDir "commit-message/commit.template").Replace('\', '/')
    if (Test-Path $templateFile) {
        & git -C $Target config --local commit.template $templateFile
        Write-Host "==> 配置 git commit.template（IDE 提交框预填模板）"
        Write-Host "    -> $templateFile"
    }
}

# 验证
Write-Host ""
Write-Host "==> 验证安装"
$bindings = @{
    "wan"      = "$toolsDir/wan/bin/$WanOut"
    "sqlguard" = "$toolsDir/sql-guard/bin/$SqlOut"
    "java-guard" = "$toolsDir/java-guard/bin/$JgOut"
}
foreach ($name in $bindings.Keys) {
    $bin = $bindings[$name]
    if (Test-Path $bin) {
        $ver = & $bin --version 2>&1 | Select-Object -First 1
        Write-Host "  ✓ $name : $ver" -ForegroundColor Green
    } else {
        Write-Host "  ✗ $name : 未找到" -ForegroundColor Red
    }
}
# 根据平台选择验证的 workflow（与 hook 的选择逻辑一致）
$wfValidate = "$toolsDir/wan/workflows/pre-commit-win.yml"
if ($IsLinux) {
    $wfValidate = "$toolsDir/wan/workflows/pre-commit-unix.yml"
}
if (Test-Path $wfValidate) {
    & $toolsDir/wan/bin/$WanOut validate $wfValidate 2>&1 | ForEach-Object { Write-Host "  $_" }
}
$wfCi = "$toolsDir/wan/workflows/ci-unix.yml"
if (Test-Path $wfCi) {
    & $toolsDir/wan/bin/$WanOut validate $wfCi 2>&1 | ForEach-Object { Write-Host "  $_" }
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor Green
Write-Host "  安装完成" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host ""
Write-Host "gates-tools/ 为 setup 生成的产物（含二进制），已加入目标项目 .gitignore，无需（也不应）提交到 git。" -ForegroundColor Yellow
Write-Host "门禁工具与规则建议整包引入 gates-toolkit（git submodule 或随仓库提交），升级时重跑本脚本即可。" -ForegroundColor Yellow
