<#
.SYNOPSIS
    Baafoo Web 控制台 UI 自动化测试运行脚本（PowerShell）

.DESCRIPTION
    完整流程：
      1. 检测 http://localhost:8084 是否可达；不可达则用 docker compose 启动 staging 集群
         （server + postgres + app-env-a + app-env-b + staging-init）。
      2. 等待 server 健康（GET /__baafoo__/api/status 返回 200）。
      3. 从容器内 .admin-credentials 抽取 admin 一次性密码，写入
         testing/7_Others/tmp/.admin-password；也支持通过环境变量
         BAAFOO_ADMIN_PASSWORD 直接提供（优先级最高）。
      4. 确保 web/node_modules 已安装。
      5. 在 web/ 目录运行 npx playwright test（globalSetup 会自动登录 admin
         并生成 admin-storage.json）。
      6. 把 HTML 报告复制到 testing/6_UITest/playwright-report/。

.PARAMETER NoAutoStart
    跳过 docker compose 自动启动，仅检测 server 是否可达；不可达直接报错退出。

.PARAMETER UpdateSnapshots
    透传给 playwright test --update-snapshots。

.PARAMETER Greedy
    透传给 playwright test --grep（只跑匹配的用例标题）。

.EXAMPLE
    .\run-ui-tests.ps1
    .\run-ui-tests.ps1 -NoAutoStart
    .\run-ui-tests.ps1 -Grep "仪表盘"
#>
[CmdletBinding()]
param(
    [switch]$NoAutoStart,
    [string]$Grep,
    [switch]$UpdateSnapshots,
    [string]$BaseUrl = 'http://localhost:8084'
)

$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

# ── 路径常量 ────────────────────────────────────────────────────────────
$ProjectRoot   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$WebDir        = Join-Path $ProjectRoot 'web'
$TmpDir        = Join-Path $ProjectRoot 'testing\7_Others\tmp'
$PasswordFile  = Join-Path $TmpDir '.admin-password'
$ReportSrcDir  = Join-Path $TmpDir 'playwright-report'
$ReportDestDir = Join-Path $PSScriptRoot 'playwright-report'
$StatusUrl     = "$BaseUrl/__baafoo__/api/status"

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    ✔ $msg" -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host "    ! $msg" -ForegroundColor Yellow }
function Write-Err([string]$msg)  { Write-Host "    x $msg" -ForegroundColor Red }

# ── 1. 检测 / 启动 server ───────────────────────────────────────────────
function Test-ServerReady([string]$url) {
    try {
        $resp = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 3 -UseBasicParsing
        return $resp.StatusCode -eq 200
    } catch {
        return $false
    }
}

Write-Step "检测 Baaoo Server ($BaseUrl)"
if (Test-ServerReady $StatusUrl) {
    Write-Ok "Server 已在运行"
} elseif ($NoAutoStart) {
    Write-Err "Server 不可达且 -NoAutoStart 已指定，退出。"
    exit 1
} else {
    Write-Warn "Server 不可达，启动 staging 集群（docker compose）..."
    $composeArgs = @('-f', 'docker-compose.yml', '-f', 'docker-compose.staging.yml', 'up', '-d',
                     'server', 'postgres', 'app-env-a', 'app-env-b', 'staging-init')
    & docker compose @composeArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Err "docker compose 启动失败（exit $LASTEXITCODE）。请确认 Docker Desktop 已运行。"
        exit $LASTEXITCODE
    }

    Write-Step "等待 server 健康（最多 120s）"
    $deadline = (Get-Date).AddSeconds(120)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-ServerReady $StatusUrl) { $ready = $true; break }
        Start-Sleep -Seconds 3
        Write-Host "." -NoNewline
    }
    Write-Host ""
    if (-not $ready) {
        Write-Err "server 在 120s 内未就绪。请手动检查：docker compose -f docker-compose.yml -f docker-compose.staging.yml logs server"
        exit 1
    }
    # app-env-a/b 需要额外几秒注册到 server
    Write-Warn "等待 agent 注册（8s）..."
    Start-Sleep -Seconds 8
    Write-Ok "staging 集群就绪"
}

# ── 2. 准备 admin 密码 ─────────────────────────────────────────────────
Write-Step "准备 admin 密码"
if ($env:BAAFOO_ADMIN_PASSWORD) {
    Write-Ok "使用环境变量 BAAFOO_ADMIN_PASSWORD"
} else {
    New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null
    $extracted = $null

    # 优先从 docker 容器抽取
    $containerCandidates = @('baafoo-server', 'baafoo-staging-server')
    foreach ($c in $containerCandidates) {
        try {
            # 用 find 定位 .admin-credentials —— 路径随 dataDir 配置变化
            # （staging 配置 dataDir: "~/.baafoo/data"，Java 不展开 ~，实际落在 /app/~/.baafoo/data/）
            $credPath = & docker exec $c sh -c 'find / -name .admin-credentials -type f 2>/dev/null | head -1' 2>$null
            if ($LASTEXITCODE -eq 0 -and $credPath) {
                $raw = & docker exec $c sh -c "cat `"$credPath`" 2>/dev/null" 2>$null
                if ($raw) {
                    # 文件格式: "  Pass:  XXX"
                    $passLine = (($raw -split "`n" | Where-Object { $_ -match 'Pass:' }) -replace 'Pass:', '').Trim()
                    if ($passLine) { $extracted = $passLine; break }
                }
            }
        } catch { }
    }

    # 回退：本地运行的 server。Java 不展开 ~，所以候选路径包括字面 ~ 目录。
    if (-not $extracted) {
        $localCandidates = @(
            (Join-Path $env:USERPROFILE '.baafoo\data\.admin-credentials'),
            (Join-Path (Get-Location) '~\.baafoo\data\.admin-credentials'),
            (Join-Path $ProjectRoot '~\.baafoo\data\.admin-credentials')
        )
        foreach ($lc in $localCandidates) {
            if (Test-Path $lc) {
                $raw = Get-Content $lc -Raw
                $passLine = (($raw -split "`n" | Where-Object { $_ -match 'Pass:' }) -replace 'Pass:', '').Trim()
                if ($passLine) { $extracted = $passLine; break }
            }
        }
    }

    if ($extracted) {
        Set-Content -Path $PasswordFile -Value $extracted -NoNewline -Encoding UTF8
        $env:BAAFOO_ADMIN_PASSWORD = $extracted
        Write-Ok "密码已抽取并写入 $PasswordFile"
    } else {
        Write-Err "无法自动获取 admin 密码。请任选其一："
        Write-Host "    1) 设置环境变量：`$env:BAAFOO_ADMIN_PASSWORD = '<admin密码>'"
        Write-Host "    2) 手工把密码写入 $PasswordFile"
        Write-Host "    3) 若 server 是本地 java -jar 启动，查看 `$HOME/.baafoo/data/.admin-credentials"
        exit 1
    }
}

# ── 3. 安装 web 依赖 ───────────────────────────────────────────────────
Write-Step "检查 web 依赖"
if (-not (Test-Path (Join-Path $WebDir 'node_modules'))) {
    Write-Warn "node_modules 缺失，运行 npm install..."
    Push-Location $WebDir
    try { & npm install; if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' } } finally { Pop-Location }
    Write-Ok "依赖安装完成"
} else {
    Write-Ok "node_modules 已存在"
}

# 确保 playwright 浏览器已下载（channel: chrome 用系统 Chrome，但仍需 @playwright/test 包）
$pwBin = Join-Path $WebDir 'node_modules\.bin\playwright.cmd'
if (-not (Test-Path $pwBin)) {
    Write-Warn "playwright CLI 缺失，重新安装依赖..."
    Push-Location $WebDir
    try { & npm install; if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' } } finally { Pop-Location }
}

# ── 4. 运行 Playwright ─────────────────────────────────────────────────
Write-Step "运行 Playwright UI 测试"
Push-Location $WebDir
try {
    $pwArgs = @('test')
    if ($UpdateSnapshots) { $pwArgs += '--update-snapshots' }
    if ($Grep)            { $pwArgs += @('--grep', $Grep) }

    Write-Host "    > npx playwright $($pwArgs -join ' ')"
    & npx playwright @pwArgs
    $testExit = $LASTEXITCODE
} finally {
    Pop-Location
}

# ── 5. 复制报告 ────────────────────────────────────────────────────────
Write-Step "汇总报告"
if (Test-Path $ReportSrcDir) {
    if (Test-Path $ReportDestDir) { Remove-Item -Recurse -Force $ReportDestDir }
    Copy-Item -Recurse -Force $ReportSrcDir $ReportDestDir
    Write-Ok "HTML 报告: $ReportDestDir\index.html"
} else {
    Write-Warn "未找到报告源目录 $ReportSrcDir"
}

# ── 6. 结果 ────────────────────────────────────────────────────────────
Write-Host ""
if ($testExit -eq 0) {
    Write-Host "==> UI 测试全部通过 ✔" -ForegroundColor Green
} else {
    Write-Host "==> UI 测试存在失败（exit $testExit）" -ForegroundColor Red
}
exit $testExit
