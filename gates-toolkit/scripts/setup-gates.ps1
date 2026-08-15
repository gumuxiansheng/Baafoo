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

# 创建 tools 目录
$toolsDir = Join-Path $Target "tools"
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
    # 检查是否已有二进制
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
    if ($needFetch) {
        Write-Host "  二进制不完整，执行下载..."
        & $fetchScript -Platform $InstallPlatform
        if ($LASTEXITCODE -ne 0) {
            Write-Error "二进制下载失败，请检查 versions.toml 中的 URL 配置"
            exit 1
        }
    } else {
        Write-Host "  二进制已存在，跳过下载"
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
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET" || exit 1
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
"$JAVAGUARD" scan "$JAVA_TARGET" --rules-dir "$TOOLS_DIR/java-guard/rules" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console || exit 1
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
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET" || exit 1
echo "✓ SqlGuard passed"
'@
    $sqlBlock = $sqlBlock.Replace('__SQL_MODULE__', $SqlModule)

    $modulesList = ($JavaModules -join ' ')
    $javaBlock = @"
echo ""
echo "=== JavaGuard Check ==="
JAVAGUARD=\"\$TOOLS_DIR/java-guard/bin/java-guard\"
JAVA_CONFIG=\"\$TOOLS_DIR/java-guard/java-guard.yml\"
GATE_CONFIG=\"\$TOOLS_DIR/java-guard/gate-config.yml\"
if [ -f \"\$JAVAGUARD.exe\" ]; then JAVAGUARD=\"\$JAVAGUARD.exe\"; fi
MODULES=\"$modulesList\"
for MOD in \$MODULES; do
  SRC=\"\$PROJECT_ROOT/\$MOD/src/main/java\"
  [ ! -d \"\$SRC\" ] && continue
  echo \"  scanning \$MOD ...\"
  \"\$JAVAGUARD\" scan \"\$SRC\" --rules-dir \"\$TOOLS_DIR/java-guard/rules\" --config \"\$JAVA_CONFIG\" --gate --gate-config \"\$GATE_CONFIG\" --diff HEAD -f console || exit 1
done
echo \"✓ JavaGuard passed\"
"@

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
rem Usage: tools\gatecheck.cmd
rem NOTE: ASCII only on purpose (cmd parses batch files using OEM codepage,
rem       UTF-8 comments before chcp can corrupt line endings)
chcp 65001 >nul
setlocal
cd /d "%~dp0.."
set "WAN=tools\wan\bin\wan.exe"
if not exist "%WAN%" (
  echo [ERROR] wan binary not found: %WAN%
  exit /b 1
)
rem Prefer Windows workflow when pwsh exists, else fall back to bash workflow
rem (same logic as the pre-commit hook)
set "WF=tools\wan\workflows\pre-commit-unix.yml"
where pwsh >nul 2>nul
if not errorlevel 1 set "WF=tools\wan\workflows\pre-commit-win.yml"
"%WAN%" run -C . "%WF%"
exit /b %ERRORLEVEL%
'@
$gatecheckCmd | Write-TextFileUtf8NoBom "$toolsDir/gatecheck.cmd"

$gatecheckSh = @'
#!/bin/sh
# 手动运行代码门禁（由 gates-toolkit 自动生成）
# 用法: bash tools/gatecheck.sh
cd "$(dirname "$0")/.." || exit 1
WAN="tools/wan/bin/wan"
if [ ! -x "$WAN" ] && [ -x "$WAN.exe" ]; then WAN="$WAN.exe"; fi
if [ ! -x "$WAN" ]; then
  echo "[ERROR] wan binary not found: $WAN" >&2
  exit 1
fi
WF="tools/wan/workflows/pre-commit-unix.yml"
if [ -f "tools/wan/workflows/pre-commit-win.yml" ] && command -v pwsh >/dev/null 2>&1; then
  WF="tools/wan/workflows/pre-commit-win.yml"
fi
"$WAN" run -C . "$WF"
'@
$gatecheckSh | Write-TextFileUtf8NoBom "$toolsDir/gatecheck.sh"

# 生成 README
Write-Host "==> 生成 tools/README.md"
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

$readme = "# tools/ — 代码门禁工具集`n`n"
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
$readme += "- Windows: ``tools\gatecheck.cmd``（双击即可）`n"
$readme += "- Linux/macOS: ``bash tools/gatecheck.sh```n`n"
$readme += "等价于以下完整命令（也可单独执行）:`n`n"
$readme += '```powershell' + "`n"
$readme += "# SqlGuard`n"
$readme += "tools/sql-guard/bin/sqlguard.exe check-diff --base HEAD -c tools/sql-guard/sqlguard.toml -f plain $BackendDir/src/main/resources`n`n"
$readme += "# JavaGuard`n"
$readme += "`$env:JAVAGUARD_PARSER_JAR = `"tools/java-guard/java-parser/java-parser.jar`"`n"
$readme += "tools/java-guard/bin/java-guard.exe scan $BackendDir/src/main/java --rules-dir tools/java-guard/rules --config tools/java-guard/java-guard.yml --gate --gate-config tools/java-guard/gate-config.yml --diff HEAD -f console`n`n"
$readme += "# wan 编排`n"
$readme += "tools/wan/bin/wan.exe run pre-commit-win -C .`n"
$readme += "````n`n"
$readme += "### CI 集成`n参考 ``.cnb.yml`` (如已存在则手动添加 code-gate job)。`n"

$readme | Write-TextFileUtf8NoBom "$toolsDir/README.md"

# 安装 hook
if ($InstallHook -and (Test-Path $GitDir)) {
    Write-Host "==> 安装 git pre-commit hook"
    $hookTarget = Join-Path $GitDir "hooks/pre-commit"
    Copy-Item "$toolsDir/hooks/pre-commit" $hookTarget -Force
    Write-Host "    -> $hookTarget"

    Write-Host "==> 安装 git commit 信息 hooks（prepare-commit-msg / commit-msg）"
    foreach ($h in @("prepare-commit-msg", "commit-msg")) {
        $t = Join-Path $GitDir "hooks/$h"
        Copy-Item "$toolsDir/hooks/$h" $t -Force
        Write-Host "    -> $t"
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
Write-Host "建议把 tools/ 加入 git: git add tools/"
