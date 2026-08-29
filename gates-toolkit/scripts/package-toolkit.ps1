#!/usr/bin/env pwsh
# gates-toolkit 打包脚本 — 生成可分发的 zip
#
# 产物（gates-toolkit-YYYYMMDD.zip）包含：
#   - gates-toolkit/   自带全部平台二进制(bin) + 规则 + 模板 + 安装脚本 + versions.toml + README
#   - 安装手册-INSTALL.md
#
# 用户拿到 zip 后：
#   1. 解压，把 gates-toolkit 文件夹放进自己的项目根目录
#   2. 在项目根目录运行 gates-toolkit/scripts/setup-gates.cmd (Windows) 或 setup-gates.sh (Linux)
#
# 用法：
#   pwsh scripts/package-toolkit.ps1                    # 输出到 toolkit 根目录
#   pwsh scripts/package-toolkit.ps1 -OutDir C:/dist    # 指定输出目录

param(
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolkitRoot = Split-Path -Parent $ScriptDir

if (-not $OutDir) { $OutDir = $ToolkitRoot }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

$Date = Get-Date -Format "yyyyMMdd"
$ZipName = "gates-toolkit-$Date.zip"
$ZipPath = Join-Path $OutDir $ZipName

# 临时组装目录
$Stage = Join-Path ([System.IO.Path]::GetTempPath()) "gates-toolkit-stage"
if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
$PkgDir = Join-Path $Stage "gates-toolkit"
New-Item -ItemType Directory -Path $PkgDir -Force | Out-Null

# 1) 顶层目录（含二进制与规则）
#    robocopy 递归镜像源目录内容到目标，避免 Copy-Item 的目录套嵌问题
foreach ($d in @("bin", "scripts", "templates")) {
    $src = Join-Path $ToolkitRoot $d
    $dest = Join-Path $PkgDir $d
    robocopy $src $dest /E /NFL /NDL /NJH /NJS | Out-Null
    if ($LASTEXITCODE -gt 7) { throw "robocopy 失败 ($LASTEXITCODE): $src -> $dest" }
    $global:LASTEXITCODE = 0
}

# 排除本地交叉编译产物（非 CI 发布，且可能覆盖内置二进制同文件）
Get-ChildItem -Path (Join-Path $PkgDir "bin/sql-guard") -Filter "sqlguard-mine*" -ErrorAction SilentlyContinue |
    Remove-Item -Force

# 2) 顶层文件
foreach ($f in @("versions.toml", "README.md")) {
    Copy-Item (Join-Path $ToolkitRoot $f) (Join-Path $PkgDir $f) -Force
}

# 3) 安装手册（zip 根目录，与 gates-toolkit 平级）
$Manual = Join-Path $ToolkitRoot "安装手册-INSTALL.md"
if (Test-Path $Manual) {
    Copy-Item $Manual (Join-Path $Stage "安装手册-INSTALL.md") -Force
} else {
    Write-Warning "未找到 安装手册-INSTALL.md，zip 内将不包含手册"
}

# 4) 打 zip
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path (Join-Path $Stage "*") -DestinationPath $ZipPath -CompressionLevel Optimal
Remove-Item $Stage -Recurse -Force

$zipSize = [math]::Round((Get-Item $ZipPath).Length / 1KB, 0)
Write-Host "打包完成: $ZipPath ($zipSize KB)"