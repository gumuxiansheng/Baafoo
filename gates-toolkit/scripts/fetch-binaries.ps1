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
# 私有库: 在 versions.toml [download] 段配置 token（或设环境变量 CNB_TOKEN），
# 内网自签 CA 设 insecure_skip_verify = "true" 跳过证书校验。
# 下载优先使用系统自带 curl.exe（与 fetch-binaries.sh 相同的实现，规避 PS5.1
# Invoke-WebRequest 内网 TLS/代理问题），无 curl.exe 时回退 Invoke-WebRequest。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("auto", "all", "windows-amd64", "linux-arm64", "linux-amd64")]
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
    } elseif ((uname -s 2>$null) -match "Darwin") {
        Write-Error "macOS 暂无预编译二进制（未发布 darwin 产物），请在 Linux/Windows 或 CI 中使用"
        exit 1
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
# 用显式 UTF-8 读取：PS5 的 Get-Content 按 ANSI 读 no-BOM UTF-8 会吞换行/乱码
$raw = [System.IO.File]::ReadAllText($ConfigFile, [System.Text.Encoding]::UTF8)
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

# ---- 下载认证/TLS 配置（可选） ----
# versions.toml [download] 段：
#   username            认证用户名，默认 "cnb"
#   token               私有库 token；未配置时回退读环境变量 CNB_TOKEN，都没有则匿名下载
#   insecure_skip_verify 内网自签 CA 时跳过 TLS 证书校验（"true" 开启）
$downloadConfig = $config["download"]
if (-not $downloadConfig) { $downloadConfig = @{} }
$downloadUsername = $downloadConfig["username"]
if (-not $downloadUsername) { $downloadUsername = "cnb" }
$downloadToken = $downloadConfig["token"]
if (-not $downloadToken) { $downloadToken = $env:CNB_TOKEN }
$skipCertVerify = $downloadConfig["insecure_skip_verify"] -eq "true"

$authHeader = $null
if ($downloadToken) {
    $authHeader = "Basic " + [Convert]::ToBase64String(
        [System.Text.Encoding]::UTF8.GetBytes("$downloadUsername`:$downloadToken"))
    Write-Host "认证: 已配置 token（用户名 $downloadUsername），用于私有库下载" -ForegroundColor DarkGray
}
if ($skipCertVerify) {
    Write-Host "TLS: insecure_skip_verify=true，跳过证书校验（仅限内网自签证书场景）" -ForegroundColor Yellow
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
        ExtraFiles = @(
            @{ SubSection = "wan.windows_amd64_shim"; Filename = "wan-shim.exe"; SubDir = "" }
        )
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
        return [System.IO.File]::ReadAllText($vf, [System.Text.Encoding]::UTF8).Trim()
    }
    return $null
}

function Write-LocalVersion($binDir, $version) {
    $vf = Get-VersionFile $binDir
    [System.IO.File]::WriteAllText($vf, $version, (New-Object System.Text.UTF8Encoding($false)))
}

# 查找可用的 curl（Windows 10 1803+ 自带 C:\Windows\System32\curl.exe）。
# 注意：不能直接用别名 "curl"（PS 里 curl 是 Invoke-WebRequest 的别名）。
function Get-Curl {
    $candidates = @("$env:SystemRoot\System32\curl.exe", "curl.exe")
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
        $found = Get-Command $c -CommandType Application -ErrorAction SilentlyContinue
        if ($found) { return $found.Source }
    }
    return $null
}

# 下载文件（重试兜底，与 fetch-binaries.sh 相同的 curl 实现）
# 优先用系统自带 curl.exe（-4 IPv4 兜底、-u token 认证、-k 跳过证书校验），
# 与 sh 脚本行为一致，规避 PS5.1 Invoke-WebRequest 在内网的 TLS/代理问题。
# curl.exe 不存在时回退 Invoke-WebRequest。
function Invoke-Download($url, $destPath) {
    $curl = Get-Curl
    if ($curl) {
        return Invoke-DownloadWithCurl $curl $url $destPath
    }

    # ---- 回退：Invoke-WebRequest（无 curl 的旧系统）----
    # cnb.cool Release 下载会 302 到 CDN 域名 asset.cnb.cool，偶发解析/IPv6 链路问题导致失败，
    # 重试 + 失败后尝试强制 IPv4（Invoke-WebRequest 无 -4 参数，重试即可覆盖大部分瞬态失败）。
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            $params = @{
                Uri         = $url
                OutFile     = $destPath
                ErrorAction = "Stop"
            }
            if ($authHeader) {
                $params.Headers = @{ Authorization = $authHeader }
            }
            if ($skipCertVerify) {
                if ($PSVersionTable.PSVersion.Major -ge 6) {
                    $params.SkipCertificateCheck = $true
                } else {
                    # PS5.1 (.NET Framework) 无 -SkipCertificateCheck，用 ServicePoint 回调跳过证书校验
                    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
                    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { return $true }
                }
            }
            Invoke-WebRequest @params
            return $true
        } catch {
            if ($attempt -lt 3) {
                Write-Host "  (第 $attempt 次尝试失败，重试...)" -ForegroundColor Yellow
                Start-Sleep -Seconds 2
            } else {
                throw
            }
        }
    }
    return $false
}

# curl.exe 实现（与 fetch-binaries.sh 的 download_file 一致）：
# 每次尝试失败后强制 IPv4（-4）再试一次，绕开 IPv6/CDN 重定向链问题。
function Invoke-DownloadWithCurl($curl, $url, $destPath) {
    $baseArgs = @("-fsSL", "--connect-timeout", "20", "--retry", "2")
    if ($downloadToken) {
        $baseArgs += @("-u", "$downloadUsername`:$downloadToken")
    }
    if ($skipCertVerify) {
        $baseArgs += "-k"
    }
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $errOut = & $curl @($baseArgs + @($url, "-o", $destPath)) 2>&1
        if ($LASTEXITCODE -eq 0) { return $true }

        $args4 = @("-4") + $baseArgs + @($url, "-o", $destPath)
        $err4Out = & $curl $args4 2>&1
        if ($LASTEXITCODE -eq 0) { return $true }

        if ($attempt -lt 3) {
            $msg = "curl exit $LASTEXITCODE $(($err4Out | Where-Object { $_ -is [string] }) -join ' ')"
            Write-Host "  (第 $attempt 次尝试失败: $msg，重试...)" -ForegroundColor Yellow
            Start-Sleep -Seconds 2
        } else {
            throw "curl 下载失败: $url (exit $LASTEXITCODE $(($err4Out | Where-Object { $_ -is [string] }) -join ' '))"
        }
    }
    return $false
}

# 比较版本号: 返回 -1 (a<b), 0 (a==b), 1 (a>b)
function Compare-Version($a, $b) {
    if (-not $a) { return -1 }
    if (-not $b) { return 1 }
    $aParts = $a.Split('.')
    $bParts = $b.Split('.')
    $maxLen = [Math]::Max($aParts.Count, $bParts.Count)
    for ($i = 0; $i -lt $maxLen; $i++) {
        $avRaw = if ($i -lt $aParts.Count) { $aParts[$i] } else { "0" }
        $bvRaw = if ($i -lt $bParts.Count) { $bParts[$i] } else { "0" }
        $av = 0; $bv = 0
        $avOk = [int]::TryParse($avRaw, [ref]$av)
        $bvOk = [int]::TryParse($bvRaw, [ref]$bv)
        if ($avOk -and $bvOk) {
            if ($av -lt $bv) { return -1 }
            if ($av -gt $bv) { return 1 }
        } else {
            # 非纯数字段（如 0.2.0-rc1）退化为字符串比较，避免 [int] 转换抛异常
            $cmp = [string]::Compare($avRaw, $bvRaw, [System.StringComparison]::OrdinalIgnoreCase)
            if ($cmp -lt 0) { return -1 }
            if ($cmp -gt 0) { return 1 }
        }
    }
    return 0
}

$downloaded = 0
$skipped = 0
$failed = 0

foreach ($tool in $tools) {
    $toolFailed = 0
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
                    # SubDir 可能为空（如 wan-shim.exe），PS5.1 的 Join-Path 不允许 Path 为空字符串，需分支处理
                    $efPath = if ($ef.SubDir) {
                        Join-Path $binDir (Join-Path $ef.SubDir $ef.Filename)
                    } else {
                        Join-Path $binDir $ef.Filename
                    }
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
            $failed++; $toolFailed++
            continue
        }

        $destPath = Join-Path $binDir $filename
        Write-Host "  $pf : $url" -ForegroundColor DarkGray

        try {
            $null = Invoke-Download -url $url -destPath $destPath
            if ($IsLinux) { & chmod +x $destPath 2>$null }
            $fileSize = (Get-Item $destPath).Length
            Write-Host "  ✓ $filename ($([math]::Round($fileSize / 1MB, 1)) MB)" -ForegroundColor Green
            $downloaded++
        } catch {
            Write-Host "  ✗ 下载失败: $($_.Exception.Message)" -ForegroundColor Red
            $failed++; $toolFailed++
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
                $failed++; $toolFailed++
                continue
            }

            $destPath = Join-Path $subDir $filename
            Write-Host "  $($ef.SubSection) : $url" -ForegroundColor DarkGray

            try {
                $null = Invoke-Download -url $url -destPath $destPath
                $fileSize = (Get-Item $destPath).Length
                Write-Host "  ✓ $filename ($([math]::Round($fileSize / 1MB, 1)) MB)" -ForegroundColor Green
                $downloaded++
            } catch {
                Write-Host "  ✗ 下载失败: $($_.Exception.Message)" -ForegroundColor Red
                $failed++; $toolFailed++
                continue
            }
        }
    }

    # 只在当前工具请求的文件都下载成功时才写入版本号
    # （部分失败时 .version 不更新，下次运行会重新尝试下载）
    if ($toolFailed -eq 0) {
        Write-LocalVersion $binDir $configVersion
    } else {
        Write-Warning "  $($tool.Name): 有 $toolFailed 个文件下载失败，.version 未更新"
    }
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })
Write-Host "  下载完成: $downloaded 个文件, $skipped 个跳过, $failed 个失败" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })
Write-Host "=========================================" -ForegroundColor $(if ($failed -gt 0) { "Yellow" } else { "Green" })

if ($failed -gt 0) { exit 1 }
exit 0
