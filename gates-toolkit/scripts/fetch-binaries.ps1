#!/usr/bin/env pwsh
# gates-toolkit 二进制下载/更新脚本
#
# 读取 versions.toml 配置，下载各平台二进制到 bin/。
# - 本地不存在 → 下载
# - 本地版本低于配置版本 → 更新
# - 本地版本等于配置版本 → 跳过
#
# 用法:
#   pwsh scripts/fetch-binaries.ps1                  # 下载当前平台
#   pwsh scripts/fetch-binaries.ps1 -Platform all    # 下载所有平台
#   pwsh scripts/fetch-binaries.ps1 -Force           # 强制重新下载

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("auto", "all", "windows-amd64", "linux-arm64")]
    [string]$Platform = "auto",

    [switch]$Force,

    [Parameter(Mandatory = $false)]
    [string]$ConfigFile
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolkitRoot = Split-Path -Parent $ScriptDir

if (-not $ConfigFile) {
    $ConfigFile = Join-Path $ToolkitRoot "versions.toml"
}

if (-not (Test-Path $ConfigFile)) {
    Write-Error "配置文件不存在: $ConfigFile"
    exit 1
}

# 检测当前平台
if ($Platform -eq "auto") {
    if ($IsWindows -or $env:OS -eq "Windows_NT") {
        $Platform = "windows-amd64"
    } elseif ($IsLinux) {
        $arch = uname -m 2>$null
        if ($arch -eq "aarch64" -or $arch -eq "arm64") {
            $Platform = "linux-arm64"
        } else {
            $Platform = "linux-amd64"
        }
    } else {
        $Platform = "windows-amd64"
        Write-Warning "无法检测平台，默认使用 windows-amd64"
    }
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  gates-toolkit 二进制下载" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "配置: $ConfigFile"
Write-Host "平台: $Platform"
Write-Host ""

# 简易 TOML 解析（versions.toml 结构固定，无需完整解析器）
$raw = Get-Content $ConfigFile -Raw
$config = @{}

# 匹配 [section] 和 key = "value"
$currentSection = ""
foreach ($line in ($raw -split "`n")) {
    $line = $line.Trim()
    if ($line -match '^\[(.+)\]$') {
        $currentSection = $matches[1].Trim()
        if (-not $config.ContainsKey($currentSection)) {
            $config[$currentSection] = @{}
        }
        continue
    }
    if ($line -match '^(\w+)\s*=\s*"(.*)"$') {
        $key = $matches[1]
        $val = $matches[2]
        $config[$currentSection][$key] = $val
    }
}

# 工具定义
$tools = @(
    @{
        Name = "wan"
        Section = "wan"
        BinDir = "bin/wan"
        VersionKey = "version"
        Platforms = @{
            "windows-amd64" = @{ SubSection = "wan.windows_amd64"; Filename = "wan.exe" }
            "linux-arm64"   = @{ SubSection = "wan.linux_arm64";   Filename = "wan-linux-arm64" }
            "linux-amd64"   = @{ SubSection = "wan.linux_amd64";   Filename = "wan-linux-amd64" }
        }
    },
    @{
        Name = "sql-guard"
        Section = "sql-guard"
        BinDir = "bin/sql-guard"
        VersionKey = "version"
        Platforms = @{
            "windows-amd64" = @{ SubSection = "sql-guard.windows_amd64"; Filename = "sqlguard.exe" }
            "linux-arm64"   = @{ SubSection = "sql-guard.linux_arm64";   Filename = "sqlguard-linux-arm64" }
            "linux-amd64"   = @{ SubSection = "sql-guard.linux_amd64";   Filename = "sqlguard-linux-amd64" }
        }
    },
    @{
        Name = "java-guard"
        Section = "java-guard"
        BinDir = "bin/java-guard"
        VersionKey = "version"
        Platforms = @{
            "windows-amd64" = @{ SubSection = "java-guard.windows_amd64"; Filename = "java-guard.exe" }
            "linux-arm64"   = @{ SubSection = "java-guard.linux_arm64";   Filename = "java-guard-linux-arm64" }
            "linux-amd64"   = @{ SubSection = "java-guard.linux_amd64";   Filename = "java-guard-linux-amd64" }
        }
        ExtraFiles = @(
            @{ SubSection = "java-guard.java_parser"; Filename = "java-parser.jar"; SubDir = "java-parser" }
        )
    }
)

# 版本文件路径
function Get-VersionFile($binDir) {
    return Join-Path $binDir ".version"
}

function Read-LocalVersion($binDir) {
    $vf = Get-VersionFile $binDir
    if (Test-Path $vf) {
        return (Get-Content $vf -Raw).Trim()
    }
    return $null
}

function Write-LocalVersion($binDir, $version) {
    $vf = Get-VersionFile $binDir
    Set-Content $vf $version -Encoding UTF8
}

# 比较版本号: 返回 -1 (a<b), 0 (a==b), 1 (a>b)
function Compare-Version($a, $b) {
    if (-not $a) { return -1 }
    if (-not $b) { return 1 }
    $aParts = $a.Split('.') | ForEach-Object { [int]$_ }
    $bParts = $b.Split('.') | ForEach-Object { [int]$_ }
    $maxLen = [Math]::Max($aParts.Count, $bParts.Count)
    for ($i = 0; $i -lt $maxLen; $i++) {
        $av = if ($i -lt $aParts.Count) { $aParts[$i] } else { 0 }
        $bv = if ($i -lt $bParts.Count) { $bParts[$i] } else { 0 }
        if ($av -lt $bv) { return -1 }
        if ($av -gt $bv) { return 1 }
    }
    return 0
}

$downloaded = 0
$skipped = 0
$failed = 0

foreach ($tool in $tools) {
    $section = $tool.Section
    $configVersion = $config[$section]["version"]

    if (-not $configVersion) {
        Write-Warning "$($tool.Name): 配置中未找到 version，跳过"
        continue
    }

    $binDir = Join-Path $ToolkitRoot $tool.BinDir
    $localVersion = Read-LocalVersion $binDir

    # 版本比较
    $needDownload = $false
    if ($Force) {
        $needDownload = $true
        $reason = "强制下载"
    } elseif (-not $localVersion) {
        $needDownload = $true
        $reason = "本地不存在"
    } elseif ((Compare-Version $localVersion $configVersion) -lt 0) {
        $needDownload = $true
        $reason = "本地 v$localVersion < 配置 v$configVersion"
    } else {
        $reason = "本地 v$localVersion = 配置 v$configVersion"
    }

    Write-Host "[$($tool.Name)] v$configVersion — $reason"

    if (-not $needDownload) {
        # 版本一致，检查文件是否都在
        $allPresent = $true
        $platformsToCheck = @()
        if ($Platform -eq "all") {
            $platformsToCheck = @($tool.Platforms.Keys) + @($tool.ExtraFiles | ForEach-Object { "extra" })
        } else {
            $platformsToCheck = @($Platform) + @($tool.ExtraFiles | ForEach-Object { "extra" })
        }

        foreach ($pf in $platformsToCheck) {
            if ($pf -eq "extra") {
                foreach ($ef in $tool.ExtraFiles) {
                    $efPath = Join-Path $binDir (Join-Path $ef.SubDir $ef.Filename)
                    if (-not (Test-Path $efPath)) { $allPresent = $false; break }
                }
            } else {
                $pfInfo = $tool.Platforms[$pf]
                if ($pfInfo) {
                    $binPath = Join-Path $binDir $pfInfo.Filename
                    if (-not (Test-Path $binPath)) { $allPresent = $false; break }
                }
            }
        }

        if ($allPresent) {
            Write-Host "  → 跳过（版本一致且文件完整）" -ForegroundColor Green
            $skipped++
            continue
        } else {
            $needDownload = $true
            $reason = "部分文件缺失"
            Write-Host "  → 版本一致但部分文件缺失，补充下载" -ForegroundColor Yellow
        }
    }

    if (-not $needDownload) { continue }

    # 确保目录存在
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null

    # 下载各平台二进制
    $platformsToDownload = @()
    if ($Platform -eq "all") {
        $platformsToDownload = @($tool.Platforms.Keys)
    } else {
        $platformsToDownload = @($Platform)
    }

    foreach ($pf in $platformsToDownload) {
        $pfInfo = $tool.Platforms[$pf]
        if (-not $pfInfo) {
            Write-Host "  $pf : 不支持，跳过" -ForegroundColor DarkGray
            continue
        }

        $subSection = $pfInfo.SubSection
        $url = $config[$subSection]["url"]
        $filename = $pfInfo.Filename

        if (-not $url -or $url -eq "") {
            Write-Host "  $pf : URL 未配置，跳过" -ForegroundColor Yellow
            $failed++
            continue
        }

        $destPath = Join-Path $binDir $filename
        Write-Host "  $pf : $url" -ForegroundColor DarkGray

        try {
            Invoke-WebRequest -Uri $url -OutFile $destPath -ErrorAction Stop
            $fileSize = (Get-Item $destPath).Length
            Write-Host "  ✓ $filename ($([math]::Round($fileSize / 1MB, 1)) MB)" -ForegroundColor Green
            $downloaded++
        } catch {
            Write-Host "  ✗ 下载失败: $($_.Exception.Message)" -ForegroundColor Red
            $failed++
            continue
        }
    }

    # 下载 java-parser.jar 等额外文件
    if ($tool.ExtraFiles) {
        foreach ($ef in $tool.ExtraFiles) {
            $url = $config[$ef.SubSection]["url"]
            $filename = $ef.Filename
            $subDir = Join-Path $binDir $ef.SubDir
            New-Item -ItemType Directory -Path $subDir -Force | Out-Null

            if (-not $url -or $url -eq "") {
                Write-Host "  $($ef.SubSection) : URL 未配置，跳过" -ForegroundColor Yellow
                $failed++
                continue
            }

            $destPath = Join-Path $subDir $filename
            Write-Host "  $($ef.SubSection) : $url" -ForegroundColor DarkGray

            try {
                Invoke-WebRequest -Uri $url -OutFile $destPath -ErrorAction Stop
                $fileSize = (Get-Item $destPath).Length
                Write-Host "  ✓ $filename ($([math]::Round($fileSize / 1MB, 1)) MB)" -ForegroundColor Green
                $downloaded++
            } catch {
                Write-Host "  ✗ 下载失败: $($_.Exception.Message)" -ForegroundColor Red
                $failed++
                continue
            }
        }
    }

    # 写入版本号
    Write-LocalVersion $binDir $configVersion
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })
Write-Host "  下载完成: $downloaded 个文件, $skipped 个跳过, $failed 个失败" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })
Write-Host "=========================================" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })

if ($failed -gt 0) { exit 1 }
exit 0
