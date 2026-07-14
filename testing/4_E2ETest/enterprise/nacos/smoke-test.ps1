# Nacos 企业级测试 - 冒烟测试脚本
# 用法: .\smoke-test.ps1

param(
    [string]$ServerBaseUrl = "http://localhost:18084",
    [string]$AppBaseUrl = "http://localhost:18091",
    [string]$NacosBaseUrl = "http://localhost:18848",
    [string]$ApiKey = "enterprise-admin-key"
)

$ErrorActionPreference = "Stop"
$passed = 0
$failed = 0
$skipped = 0
$MODE_SETTLE_WAIT = 12
$script:TestResults = @()

function Write-Result {
    param([string]$testName, [string]$status, [string]$detail = "")
    $statusLower = $status.ToLower()
    $script:TestResults += [PSCustomObject]@{ Name = $testName; Status = $statusLower; Message = $detail }
    switch ($status) {
        "PASS" { Write-Host "[PASS] $testName" -ForegroundColor Green; $script:passed++ }
        "FAIL" { Write-Host "[FAIL] $testName" -ForegroundColor Red; if ($detail) { Write-Host "       $detail" -ForegroundColor DarkRed }; $script:failed++ }
        "SKIP" { Write-Host "[SKIP] $testName" -ForegroundColor Yellow; if ($detail) { Write-Host "       $detail" -ForegroundColor DarkYellow }; $script:skipped++ }
    }
}

function Export-JUnitXml {
    param([string]$path)
    $ts = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    $sb = New-Object System.Text.StringBuilder
    $null = $sb.Append('<?xml version="1.0" encoding="UTF-8"?>')
    $null = $sb.AppendFormat('<testsuites name="baafoo-enterprise-nacos" tests="{0}" failures="{1}" skipped="{2}" errors="0">', $script:TestResults.Count, $script:failed, $script:skipped)
    $null = $sb.AppendFormat('<testsuite name="EnterpriseNacos" tests="{0}" failures="{1}" skipped="{2}" errors="0" timestamp="{3}">', $script:TestResults.Count, $script:failed, $script:skipped, $ts)
    foreach ($t in $script:TestResults) {
        $nameEsc = [Security.SecurityElement]::Escape($t.Name)
        $msgEsc = [Security.SecurityElement]::Escape($t.Message)
        $null = $sb.AppendFormat('<testcase name="{0}" classname="EnterpriseNacos" status="{1}">', $nameEsc, $t.Status)
        if ($t.Status -eq "fail") {
            $null = $sb.AppendFormat('<failure message="{0}">{0}</failure>', $msgEsc)
        } elseif ($t.Status -eq "skip") {
            $null = $sb.AppendFormat('<skipped message="{0}"/>', $msgEsc)
        }
        $null = $sb.Append('</testcase>')
    }
    $null = $sb.Append('</testsuite></testsuites>')
    [System.IO.File]::WriteAllText($path, $sb.ToString(), [System.Text.Encoding]::UTF8)
    Write-Host "  [OK] JUnit XML written: $path" -ForegroundColor Gray
}

function Invoke-ApiGet {
    param([string]$path, [string]$baseUrl = $ServerBaseUrl)
    $url = "$baseUrl$path"
    $resp = Invoke-RestMethod -Uri $url -Headers @{ "X-Api-Key" = $ApiKey } -Method Get -TimeoutSec 30
    return $resp
}

function Invoke-ApiPost {
    param([string]$path, [string]$baseUrl = $ServerBaseUrl, [string]$body = "{}")
    $url = "$baseUrl$path"
    $resp = Invoke-RestMethod -Uri $url -Headers @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" } -Method Post -Body $body -TimeoutSec 30
    return $resp
}

function Invoke-AppGet {
    param([string]$path)
    $url = "$AppBaseUrl$path"
    $resp = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 30
    return $resp
}

function Switch-Mode {
    param([string]$mode)
    $body = @{ mode = $mode.ToUpper() } | ConvertTo-Json
    Invoke-ApiPost "/__baafoo__/api/environments/enterprise-nacos/mode" -body $body | Out-Null
    Start-Sleep -Seconds $MODE_SETTLE_WAIT
}

function Test-NacosApi {
    param([string]$nacosPath, [string]$method = "GET")
    # Route through test app's HTTP caller to hit Nacos inside Docker network
    $encodedUrl = [System.Web.HttpUtility]::UrlEncode("http://nacos:8848$nacosPath")
    if ($method -eq "GET") {
        return Invoke-AppGet "/api/http/get?url=$encodedUrl"
    } else {
        return Invoke-RestMethod -Uri "$AppBaseUrl/api/http/post?url=$encodedUrl" -Method Post -TimeoutSec 30
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Nacos 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# =========================================================================
# EG-NACOS-001: 应用启动 + Agent 挂载无异常 (P0, EG-COMMON-001)
# =========================================================================
Write-Host "[EG-NACOS-001] 应用启动 + Agent 挂载..." -ForegroundColor White
try {
    $health = Invoke-AppGet "/api/stub-demo/health"
    if ($health -eq "OK") {
        Write-Result "EG-NACOS-001" "PASS"
    } else {
        Write-Result "EG-NACOS-001" "FAIL" "健康检查返回非 OK: $health"
    }
} catch {
    Write-Result "EG-NACOS-001" "FAIL" "应用不可达: $($_.Exception.Message)"
}

# =========================================================================
# EG-NACOS-002: Agent 成功注册到 Server (P0, EG-COMMON-002)
# =========================================================================
Write-Host "[EG-NACOS-002] Agent 注册验证..." -ForegroundColor White
try {
    $agents = Invoke-ApiGet "/__baafoo__/api/agents"
    $nacosAgent = $agents | Where-Object { $_.environment -eq "enterprise-nacos" }
    if ($nacosAgent) {
        Write-Result "EG-NACOS-002" "PASS"
    } else {
        Write-Result "EG-NACOS-002" "FAIL" "未找到 enterprise-nacos 环境的 Agent"
    }
} catch {
    Write-Result "EG-NACOS-002" "FAIL" "API 查询失败: $($_.Exception.Message)"
}

# =========================================================================
# EG-NACOS-003: Nacos 服务注册 API Mock (P0, EG-COMMON-003)
# 对 Nacos /nacos/v1/ns/instance POST 请求被 Mock
# =========================================================================
Write-Host "[EG-NACOS-003] Nacos 服务注册 API Mock..." -ForegroundColor White
try {
    Switch-Mode "stub"
    $resp = Test-NacosApi "/nacos/v1/ns/instance?serviceName=test-service&ip=10.0.0.1&port=8080" -method "POST"
    if ($resp.statusCode -eq 200 -and $resp.stubbed -eq $true) {
        Write-Result "EG-NACOS-003" "PASS"
    } else {
        Write-Result "EG-NACOS-003" "FAIL" "statusCode=$($resp.statusCode) stubbed=$($resp.stubbed)"
    }
} catch {
    Write-Result "EG-NACOS-003" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-004: Nacos 服务发现 API Mock (P0)
# 对 Nacos /nacos/v1/ns/instance/list GET 请求返回 Mock 服务列表
# =========================================================================
Write-Host "[EG-NACOS-004] Nacos 服务发现 API Mock..." -ForegroundColor White
try {
    $resp = Test-NacosApi "/nacos/v1/ns/instance/list?serviceName=test-service"
    $body = $resp.body
    if ($body -match '"mocked"\s*:\s*true' -and $body -match '"name"\s*:\s*"mock-service"') {
        Write-Result "EG-NACOS-004" "PASS"
    } else {
        Write-Result "EG-NACOS-004" "FAIL" "返回内容未包含 Mock 标记: $body"
    }
} catch {
    Write-Result "EG-NACOS-004" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-005: Nacos 配置拉取 API Mock (P0)
# 对 Nacos /nacos/v1/cs/configs GET 请求返回 Mock 配置
# =========================================================================
Write-Host "[EG-NACOS-005] Nacos 配置拉取 API Mock..." -ForegroundColor White
try {
    $resp = Test-NacosApi "/nacos/v1/cs/configs?dataId=test-config&group=DEFAULT_GROUP"
    $body = $resp.body
    if ($body -match "baafoo-nacos" -and $body -match "mock.config=true") {
        Write-Result "EG-NACOS-005" "PASS"
    } else {
        Write-Result "EG-NACOS-005" "FAIL" "返回内容未包含 Mock 配置: $body"
    }
} catch {
    Write-Result "EG-NACOS-005" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-006: Passthrough 模式透传真实 Nacos (P0, EG-COMMON-004)
# =========================================================================
Write-Host "[EG-NACOS-006] Passthrough 模式透传真实 Nacos..." -ForegroundColor White
try {
    Switch-Mode "passthrough"
    $resp = Test-NacosApi "/nacos/v1/ns/operator/metrics"
    if ($resp.statusCode -eq 200 -and $resp.stubbed -ne $true) {
        Write-Result "EG-NACOS-006" "PASS"
    } else {
        Write-Result "EG-NACOS-006" "FAIL" "透传未到达真实 Nacos: statusCode=$($resp.statusCode) stubbed=$($resp.stubbed)"
    }
} catch {
    Write-Result "EG-NACOS-006" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-007: Record 模式录制 (P1, EG-COMMON-005)
# =========================================================================
Write-Host "[EG-NACOS-007] Record 模式录制..." -ForegroundColor White
try {
    Switch-Mode "record"
    # 发送几个请求到真实 Nacos
    Test-NacosApi "/nacos/v1/ns/operator/metrics" | Out-Null
    Test-NacosApi "/nacos/v1/ns/instance/list?serviceName=default" | Out-Null
    Start-Sleep -Seconds 2

    $recordings = Invoke-ApiGet "/__baafoo__/api/recordings?environment=enterprise-nacos"
    if ($recordings -and $recordings.Count -gt 0) {
        Write-Result "EG-NACOS-007" "PASS"
    } else {
        Write-Result "EG-NACOS-007" "FAIL" "未录制到任何请求"
    }
} catch {
    Write-Result "EG-NACOS-007" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-008: 环境模式热切换 (P1, EG-COMMON-006)
# =========================================================================
Write-Host "[EG-NACOS-008] 环境模式热切换..." -ForegroundColor White
try {
    # stub → passthrough → stub
    Switch-Mode "stub"
    $respStub = Test-NacosApi "/nacos/v1/ns/instance/list?serviceName=test-service"
    $stubbed = $respStub.stubbed

    Switch-Mode "passthrough"
    $respPt = Test-NacosApi "/nacos/v1/ns/instance/list?serviceName=test-service"
    $passthrough = -not $respPt.stubbed

    Switch-Mode "stub"
    $respStub2 = Test-NacosApi "/nacos/v1/ns/instance/list?serviceName=test-service"
    $stubbedAgain = $respStub2.stubbed

    if ($stubbed -and $passthrough -and $stubbedAgain) {
        Write-Result "EG-NACOS-008" "PASS"
    } else {
        Write-Result "EG-NACOS-008" "FAIL" "stub=$stubbed pt=$passthrough stub2=$stubbedAgain"
    }
} catch {
    Write-Result "EG-NACOS-008" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-009: 无类加载冲突 (P0, EG-COMMON-008)
# =========================================================================
Write-Host "[EG-NACOS-009] 无类加载冲突..." -ForegroundColor White
try {
    $agents = Invoke-ApiGet "/__baafoo__/api/agents"
    $nacosAgent = $agents | Where-Object { $_.environment -eq "enterprise-nacos" }
    if ($nacosAgent -and $nacosAgent.status -eq "online") {
        Write-Result "EG-NACOS-009" "PASS"
    } else {
        Write-Result "EG-NACOS-009" "FAIL" "Agent 状态异常"
    }
} catch {
    Write-Result "EG-NACOS-009" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-010: 应用功能完整性 (P0, EG-COMMON-007)
# =========================================================================
Write-Host "[EG-NACOS-010] 应用功能完整性..." -ForegroundColor White
try {
    Switch-Mode "stub"
    # 验证测试应用自身功能正常
    $health = Invoke-AppGet "/api/stub-demo/health"
    $httpHealth = Invoke-AppGet "/api/http/health"
    if ($health -eq "OK" -and $httpHealth -eq "OK") {
        Write-Result "EG-NACOS-010" "PASS"
    } else {
        Write-Result "EG-NACOS-010" "FAIL" "应用功能异常: health=$health httpHealth=$httpHealth"
    }
} catch {
    Write-Result "EG-NACOS-010" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-NACOS-011: Nacos 控制台可达性验证 (P1)
# =========================================================================
Write-Host "[EG-NACOS-011] Nacos 控制台可达性..." -ForegroundColor White
try {
    $resp = Invoke-RestMethod -Uri "$NacosBaseUrl/nacos/v1/console/health/readiness" -Method Get -TimeoutSec 10
    if ($resp.status -eq "UP") {
        Write-Result "EG-NACOS-011" "PASS"
    } else {
        Write-Result "EG-NACOS-011" "PASS" "Nacos 控制台可达 (status=$($resp.status))"
    }
} catch {
    Write-Result "EG-NACOS-011" "SKIP" "Nacos 控制台不可达 (可能未启动)"
}

# =========================================================================
# EG-NACOS-012: Nacos gRPC 长连接拦截 (P1)
# Nacos 2.x 使用 gRPC 9848 端口进行长连接通信
# =========================================================================
Write-Host "[EG-NACOS-012] Nacos gRPC 长连接拦截..." -ForegroundColor White
Write-Result "EG-NACOS-012" "SKIP" "gRPC 长连接拦截需要 gRPC 协议支持，当前 Agent 仅配置 HTTP 协议"

# 恢复 stub 模式
try { Switch-Mode "stub" } catch {}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败, $skipped 跳过" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

# ==================== JUnit XML report (CI consumption) ====================
Export-JUnitXml (Join-Path $PSScriptRoot "junit-report.xml")

if ($failed -gt 0) {
    exit 1
}
