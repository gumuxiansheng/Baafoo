#!/usr/bin/env pwsh
# gates-toolkit 一键安装脚本
# 用法:
#   pwsh scripts/setup-gates.ps1 -Target C:/my/project -ProjectType spring-boot
#   pwsh scripts/setup-gates.ps1 -Target C:/my/project -ProjectType multi-module -SqlModule baafoo-server

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
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
$GitDir = Join-Path $Target ".git"
if (-not (Test-Path $GitDir)) {
    Write-Warning "目标不是 git 仓库: $Target"
    if (-not $Force) {
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
            $SqlModule = $candidates[0]
            Write-Host "[auto] SQL 模块: $SqlModule"
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
        Write-Error "未找到 Java 模块，请指定 -JavaModules"
        exit 1
    }
}

# 创建 tools 目录
$toolsDir = Join-Path $Target "tools"
if (Test-Path $toolsDir) {
    if (-not $Force) {
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

# 确保工具二进制存在（必要时从配置 URL 下载）
Write-Host "==> 检查工具二进制"
$fetchScript = Join-Path $ToolkitRoot "scripts/fetch-binaries.ps1"
if (Test-Path $fetchScript) {
    # 检查是否已有二进制
    $needFetch = $false
    $bins = @(
        "$ToolkitRoot/bin/wan/wan.exe",
        "$ToolkitRoot/bin/sql-guard/sqlguard.exe",
        "$ToolkitRoot/bin/java-guard/java-guard.exe",
        "$ToolkitRoot/bin/java-guard/java-parser/java-parser.jar"
    )
    foreach ($b in $bins) {
        if (-not (Test-Path $b)) { $needFetch = $true; break }
    }
    if ($needFetch) {
        Write-Host "  二进制不完整，执行下载..."
        & $fetchScript -Platform windows-amd64
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
    @{ Src = "$ToolkitRoot/bin/wan/wan.exe"; Dst = "$toolsDir/wan/bin/" },
    @{ Src = "$ToolkitRoot/bin/sql-guard/sqlguard.exe"; Dst = "$toolsDir/sql-guard/bin/" },
    @{ Src = "$ToolkitRoot/bin/java-guard/java-guard.exe"; Dst = "$toolsDir/java-guard/bin/" },
    @{ Src = "$ToolkitRoot/bin/java-guard/java-parser/java-parser.jar"; Dst = "$toolsDir/java-guard/java-parser/" }
)
foreach ($bf in $binFiles) {
    if (Test-Path $bf.Src) {
        Copy-Item $bf.Src $bf.Dst -Force
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
    Get-Content "$ToolkitRoot/templates/sql-guard/sql-guard-spring-boot.toml" -Raw
} else {
    $tpl = Get-Content "$ToolkitRoot/templates/sql-guard/sql-guard-multi-module.toml" -Raw
    $tpl.Replace("{{SQL_MODULE}}", $SqlModule)
}
$sqlTpl | Set-Content "$toolsDir/sql-guard/sqlguard.toml" -Encoding UTF8
Copy-Item "$ToolkitRoot/templates/sql-guard/sqlguard.rules.toml" "$toolsDir/sql-guard/" -Force

# 渲染 java-guard 配置
Write-Host "==> 生成 java-guard 配置"
if ($ProjectType -eq "spring-boot") {
    Copy-Item "$ToolkitRoot/templates/java-guard/java-guard--spring-boot.yml" "$toolsDir/java-guard/java-guard.yml" -Force
} else {
    $modulesList = ($JavaModules -join " ")
    $modulesArrayPs = ($JavaModules | ForEach-Object { '"' + $_ + '"' }) -join ", "
    $tpl = Get-Content "$ToolkitRoot/templates/java-guard/java-guard--multi-module.yml" -Raw
    $tpl = $tpl.Replace("{{MODULES_LIST}}", $modulesList)
    $tpl | Set-Content "$toolsDir/java-guard/java-guard.yml" -Encoding UTF8
}
Copy-Item "$ToolkitRoot/templates/java-guard/gate-config.yml" "$toolsDir/java-guard/" -Force

# 渲染 wan workflow
Write-Host "==> 生成 wan workflow"
if ($ProjectType -eq "spring-boot") {
    $winTpl = Get-Content "$ToolkitRoot/templates/wan/workflows/pre-commit--spring-boot.yml" -Raw
    $winTpl = $winTpl.Replace("{{BACKEND_DIR}}", $BackendDir)
    $winTpl | Set-Content "$toolsDir/wan/workflows/pre-commit-win.yml" -Encoding UTF8

    $unixTpl = Get-Content "$ToolkitRoot/templates/wan/workflows/pre-commit--spring-boot-unix.yml" -Raw
    $unixTpl = $unixTpl.Replace("{{BACKEND_DIR}}", $BackendDir)
    $unixTpl | Set-Content "$toolsDir/wan/workflows/pre-commit-unix.yml" -Encoding UTF8
} else {
    $modulesList = ($JavaModules -join " ")
    $modulesArrayPs = ($JavaModules | ForEach-Object { '"' + $_ + '"' }) -join ", "

    $winTpl = Get-Content "$ToolkitRoot/templates/wan/workflows/pre-commit--multi-module.yml" -Raw
    $winTpl = $winTpl.Replace("{{SQL_MODULE}}", $SqlModule)
    $winTpl = $winTpl.Replace("{{MODULES_ARRAY_PS}}", $modulesArrayPs)
    $winTpl | Set-Content "$toolsDir/wan/workflows/pre-commit-win.yml" -Encoding UTF8

    $unixTpl = Get-Content "$ToolkitRoot/templates/wan/workflows/pre-commit--multi-module-unix.yml" -Raw
    $unixTpl = $unixTpl.Replace("{{SQL_MODULE}}", $SqlModule)
    $unixTpl = $unixTpl.Replace("{{MODULES_LIST}}", $modulesList)
    $unixTpl | Set-Content "$toolsDir/wan/workflows/pre-commit-unix.yml" -Encoding UTF8
}

# 渲染 hook
Write-Host "==> 生成 pre-commit hook"
$hookTpl = Get-Content "$ToolkitRoot/templates/hooks/pre-commit.template" -Raw
if ($ProjectType -eq "spring-boot") {
    $sqlBlock = @'
echo ""
echo "=== SqlGuard Check ==="
SQLGUARD="$TOOLS_DIR/sql-guard/bin/sqlguard"
SQL_CONFIG="$TOOLS_DIR/sql-guard/sqlguard.toml"
SQL_TARGET="$PROJECT_ROOT/__BACKEND__/src/main/resources"
if [ -f "$SQLGUARD.exe" ]; then SQLGUARD="$SQLGUARD.exe"; fi
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET"
echo "? SqlGuard passed"
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
"$JAVAGUARD" scan "$JAVA_TARGET" --config "$JAVA_CONFIG" --gate --gate-config "$GATE_CONFIG" --diff HEAD -f console
echo "? JavaGuard passed"
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
"$SQLGUARD" check-diff --base HEAD -c "$SQL_CONFIG" -f plain "$SQL_TARGET"
echo "? SqlGuard passed"
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
  \"\$JAVAGUARD\" scan \"\$SRC\" --config \"\$JAVA_CONFIG\" --gate --gate-config \"\$GATE_CONFIG\" --diff HEAD -f console
done
echo \"? JavaGuard passed\"
"@

    $hookTpl = $hookTpl.Replace("{{FALLBACK_SQL_BLOCK}}", $sqlBlock)
    $hookTpl = $hookTpl.Replace("{{FALLBACK_JAVA_BLOCK}}", $javaBlock)
}
$hookTpl | Set-Content "$toolsDir/hooks/pre-commit" -Encoding UTF8

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
$readme += "### 手动运行`n"
$readme += '```powershell' + "`n"
$readme += "# SqlGuard`n"
$readme += "tools/sql-guard/bin/sqlguard.exe check-diff --base HEAD -c tools/sql-guard/sqlguard.toml -f plain $BackendDir/src/main/resources`n`n"
$readme += "# JavaGuard`n"
$readme += "`$env:JAVAGUARD_PARSER_JAR = `"tools/java-guard/java-parser/java-parser.jar`"`n"
$readme += "tools/java-guard/bin/java-guard.exe scan $BackendDir/src/main/java --config tools/java-guard/java-guard.yml --gate --gate-config tools/java-guard/gate-config.yml --diff HEAD -f console`n`n"
$readme += "# wan 编排`n"
$readme += "tools/wan/bin/wan.exe run pre-commit-win -C .`n"
$readme += "````n`n"
$readme += "### CI 集成`n参考 ``.cnb.yml`` (如已存在则手动添加 code-gate job)。`n"

$readme | Set-Content "$toolsDir/README.md" -Encoding UTF8

# 安装 hook
if ($InstallHook -and (Test-Path $GitDir)) {
    Write-Host "==> 安装 git pre-commit hook"
    $hookTarget = Join-Path $GitDir "hooks/pre-commit"
    Copy-Item "$toolsDir/hooks/pre-commit" $hookTarget -Force
    Write-Host "    -> $hookTarget"
}

# 验证
Write-Host ""
Write-Host "==> 验证安装"
$bindings = @{
    "wan"      = "$toolsDir/wan/bin/wan.exe"
    "sqlguard" = "$toolsDir/sql-guard/bin/sqlguard.exe"
    "java-guard" = "$toolsDir/java-guard/bin/java-guard.exe"
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
$wf = "$toolsDir/wan/workflows/pre-commit-win.yml"
if (Test-Path $wf) {
    & $toolsDir/wan/bin/wan.exe validate $wf 2>&1 | ForEach-Object { Write-Host "  $_" }
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor Green
Write-Host "  安装完成" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host ""
Write-Host "建议把 tools/ 加入 git: git add tools/"
