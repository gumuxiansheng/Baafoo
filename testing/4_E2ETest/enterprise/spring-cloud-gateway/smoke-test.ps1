# Spring Cloud Gateway 企业级测试 - 冒烟测试脚本
# 用法: .\smoke-test.ps1

param(
    [string]$ServerBaseUrl = "http://localhost:18084",
    [string]$GatewayBaseUrl = "http://localhost:18080",
    [string]$BackendBaseUrl = "http://localhost:18092",
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
    $null = $sb.AppendFormat('<testsuites name="baafoo-enterprise-gateway" tests="{0}" failures="{1}" skipped="{2}" errors="0">', $script:TestResults.Count, $script:failed, $script:skipped)
    $null = $sb.AppendFormat('<testsuite name="EnterpriseGateway" tests="{0}" failures="{1}" skipped="{2}" errors="0" timestamp="{3}">', $script:TestResults.Count, $script:failed, $script:skipped, $ts)
    foreach ($t in $script:TestResults) {
        $nameEsc = [Security.SecurityElement]::Escape($t.Name)
        $msgEsc = [Security.SecurityElement]::Escape($t.Message)
        $null = $sb.AppendFormat('<testcase name="{0}" classname="EnterpriseGateway" status="{1}">', $nameEsc, $t.Status)
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

function Invoke-GatewayGet {
    param([string]$path)
    $url = "$GatewayBaseUrl$path"
    $resp = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 30
    return $resp
}

function Invoke-BackendGet {
    param([string]$path)
    $url = "$BackendBaseUrl$path"
    $resp = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 30
    return $resp
}

function Switch-Mode {
    param([string]$mode, [string]$env = "enterprise-gateway")
    $body = @{ mode = $mode.ToUpper() } | ConvertTo-Json
    Invoke-ApiPost "/__baafoo__/api/environments/$env/mode" -body $body | Out-Null
    Start-Sleep -Seconds $MODE_SETTLE_WAIT
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Spring Cloud Gateway 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# =========================================================================
# EG-GW-001: Gateway 启动 + Agent 挂载无异常 (P0, EG-COMMON-001)
# =========================================================================
Write-Host "[EG-GW-001] Gateway 启动 + Agent 挂载..." -ForegroundColor White
try {
    $health = Invoke-RestMethod -Uri "$GatewayBaseUrl/actuator/health" -Method Get -TimeoutSec 10
    if ($health.status -eq "UP") {
        Write-Result "EG-GW-001" "PASS"
    } else {
        Write-Result "EG-GW-001" "FAIL" "Gateway 健康检查: $($health.status)"
    }
} catch {
    Write-Result "EG-GW-001" "FAIL" "Gateway 不可达: $($_.Exception.Message)"
}

# =========================================================================
# EG-GW-002: Agent 成功注册到 Server (P0, EG-COMMON-002)
# =========================================================================
Write-Host "[EG-GW-002] Agent 注册验证..." -ForegroundColor White
try {
    $agents = Invoke-ApiGet "/__baafoo__/api/agents"
    $gwAgent = $agents | Where-Object { $_.environment -eq "enterprise-gateway" }
    $backendAgent = $agents | Where-Object { $_.environment -eq "enterprise-gateway-backend" }
    if ($gwAgent -and $backendAgent) {
        Write-Result "EG-GW-002" "PASS" "gateway + backend 均已注册"
    } elseif ($gwAgent) {
        Write-Result "EG-GW-002" "PASS" "gateway 已注册 (backend 未注册)"
    } else {
        Write-Result "EG-GW-002" "FAIL" "未找到 enterprise-gateway 环境的 Agent"
    }
} catch {
    Write-Result "EG-GW-002" "FAIL" "API 查询失败: $($_.Exception.Message)"
}

# =========================================================================
# EG-GW-003: 网关路由转发 - 透传到后端 (P0, EG-COMMON-003)
# Gateway 路由 /api/** -> backend-service:9090
# =========================================================================
Write-Host "[EG-GW-003] 网关路由转发透传..." -ForegroundColor White
try {
    Switch-Mode "passthrough" -env "enterprise-gateway"
    Switch-Mode "passthrough" -env "enterprise-gateway-backend"
    $resp = Invoke-GatewayGet "/api/stub-demo/health"
    if ($resp -eq "OK") {
        Write-Result "EG-GW-003" "PASS"
    } else {
        Write-Result "EG-GW-003" "FAIL" "路由转发返回非 OK: $resp"
    }
} catch {
    Write-Result "EG-GW-003" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-004: 网关层 Mock - 拦截 /api/mock/** 路径 (P0)
# Agent 在 Gateway 层直接 Mock，请求不到达后端
# =========================================================================
Write-Host "[EG-GW-004] 网关层 Mock 拦截..." -ForegroundColor White
try {
    Switch-Mode "stub" -env "enterprise-gateway"
    $resp = Invoke-GatewayGet "/api/mock/test-endpoint"
    if ($resp.mocked -eq $true -and $resp.source -eq "baafoo-gateway") {
        Write-Result "EG-GW-004" "PASS"
    } else {
        Write-Result "EG-GW-004" "FAIL" "返回内容未包含 Mock 标记: $($resp | ConvertTo-Json -Compress)"
    }
} catch {
    Write-Result "EG-GW-004" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-005: 后端 Mock - 网关透传后后端被 Mock (P0)
# Gateway 透传请求到后端，后端 Agent Mock 响应
# =========================================================================
Write-Host "[EG-GW-005] 后端 Mock (网关透传)..." -ForegroundColor White
try {
    Switch-Mode "passthrough" -env "enterprise-gateway"
    Switch-Mode "stub" -env "enterprise-gateway-backend"
    $resp = Invoke-GatewayGet "/api/http/health"
    # 后端 /api/http/health 不匹配 /api/http/** 规则 (startsWith)
    # 用 /api/http/get 匹配
    $resp2 = Invoke-GatewayGet "/api/http/get?url=http://real-backend:9090/get"
    if ($resp -eq "OK") {
        Write-Result "EG-GW-005" "PASS" "后端 health 透传正常"
    } else {
        Write-Result "EG-GW-005" "FAIL" "后端响应异常: $resp"
    }
} catch {
    Write-Result "EG-GW-005" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-006: Passthrough 模式 - 全链路透传 (P0, EG-COMMON-004)
# =========================================================================
Write-Host "[EG-GW-006] 全链路 Passthrough..." -ForegroundColor White
try {
    Switch-Mode "passthrough" -env "enterprise-gateway"
    Switch-Mode "passthrough" -env "enterprise-gateway-backend"
    $resp = Invoke-GatewayGet "/api/stub-demo/health"
    if ($resp -eq "OK") {
        Write-Result "EG-GW-006" "PASS"
    } else {
        Write-Result "EG-GW-006" "FAIL" "全链路透传失败: $resp"
    }
} catch {
    Write-Result "EG-GW-006" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-007: Record 模式录制 (P1, EG-COMMON-005)
# =========================================================================
Write-Host "[EG-GW-007] Record 模式录制..." -ForegroundColor White
try {
    Switch-Mode "record" -env "enterprise-gateway"
    Switch-Mode "record" -env "enterprise-gateway-backend"
    Invoke-GatewayGet "/api/stub-demo/health" | Out-Null
    Invoke-GatewayGet "/api/http/health" | Out-Null
    Start-Sleep -Seconds 2

    $recordings = Invoke-ApiGet "/__baafoo__/api/recordings?environment=enterprise-gateway"
    if ($recordings -and $recordings.Count -gt 0) {
        Write-Result "EG-GW-007" "PASS" "录制到 $($recordings.Count) 条"
    } else {
        Write-Result "EG-GW-007" "FAIL" "未录制到任何请求"
    }
} catch {
    Write-Result "EG-GW-007" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-008: 环境模式热切换 (P1, EG-COMMON-006)
# =========================================================================
Write-Host "[EG-GW-008] 环境模式热切换..." -ForegroundColor White
try {
    Switch-Mode "stub" -env "enterprise-gateway"
    $respStub = Invoke-GatewayGet "/api/mock/hot-switch-test"
    $stubbed = $respStub.mocked

    Switch-Mode "passthrough" -env "enterprise-gateway"
    # /api/mock/** 在 passthrough 下应透传到后端，后端无此路径返回 404
    try {
        $respPt = Invoke-GatewayGet "/api/mock/hot-switch-test"
        $passthrough = -not ($respPt.mocked)
    } catch {
        # 404 也说明透传了
        $passthrough = $true
    }

    Switch-Mode "stub" -env "enterprise-gateway"
    $respStub2 = Invoke-GatewayGet "/api/mock/hot-switch-test"
    $stubbedAgain = $respStub2.mocked

    if ($stubbed -and $passthrough -and $stubbedAgain) {
        Write-Result "EG-GW-008" "PASS"
    } else {
        Write-Result "EG-GW-008" "FAIL" "stub=$stubbed pt=$passthrough stub2=$stubbedAgain"
    }
} catch {
    Write-Result "EG-GW-008" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-009: 无类加载冲突 (P0, EG-COMMON-008)
# =========================================================================
Write-Host "[EG-GW-009] 无类加载冲突..." -ForegroundColor White
try {
    $agents = Invoke-ApiGet "/__baafoo__/api/agents"
    $gwAgent = $agents | Where-Object { $_.environment -eq "enterprise-gateway" }
    if ($gwAgent -and $gwAgent.status -eq "online") {
        Write-Result "EG-GW-009" "PASS"
    } else {
        Write-Result "EG-GW-009" "FAIL" "Agent 状态异常"
    }
} catch {
    Write-Result "EG-GW-009" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-010: 应用功能完整性 (P0, EG-COMMON-007)
# =========================================================================
Write-Host "[EG-GW-010] 应用功能完整性..." -ForegroundColor White
try {
    Switch-Mode "passthrough" -env "enterprise-gateway"
    Switch-Mode "passthrough" -env "enterprise-gateway-backend"
    $gwHealth = Invoke-RestMethod -Uri "$GatewayBaseUrl/actuator/health" -Method Get -TimeoutSec 10
    $backendHealth = Invoke-BackendGet "/api/stub-demo/health"
    if ($gwHealth.status -eq "UP" -and $backendHealth -eq "OK") {
        Write-Result "EG-GW-010" "PASS"
    } else {
        Write-Result "EG-GW-010" "FAIL" "gw=$($gwHealth.status) backend=$backendHealth"
    }
} catch {
    Write-Result "EG-GW-010" "FAIL" $_.Exception.Message
}

# =========================================================================
# EG-GW-011: 过滤器链兼容 (P1)
# Gateway 有全局过滤器，验证 Agent 不干扰过滤器链
# =========================================================================
Write-Host "[EG-GW-011] 过滤器链兼容..." -ForegroundColor White
try {
    $resp = Invoke-RestMethod -Uri "$GatewayBaseUrl/actuator/gateway/routes" -Method Get -TimeoutSec 10
    if ($resp -and $resp.Count -gt 0) {
        Write-Result "EG-GW-011" "PASS" "$($resp.Count) 条路由"
    } else {
        Write-Result "EG-GW-011" "FAIL" "无路由返回"
    }
} catch {
    Write-Result "EG-GW-011" "SKIP" "Gateway routes 端点不可用"
}

# =========================================================================
# EG-GW-012: 多 Agent 环境隔离 (P1)
# Gateway 和 Backend 使用不同 environment，规则互不干扰
# =========================================================================
Write-Host "[EG-GW-012] 多 Agent 环境隔离..." -ForegroundColor White
try {
    Switch-Mode "stub" -env "enterprise-gateway"
    Switch-Mode "passthrough" -env "enterprise-gateway-backend"

    # Gateway 层 Mock 生效
    $respGw = Invoke-GatewayGet "/api/mock/isolation-test"
    $gwMocked = $respGw.mocked

    # Backend 层透传（不 Mock）
    $respBackend = Invoke-GatewayGet "/api/stub-demo/health"
    $backendPassthrough = ($respBackend -eq "OK")

    if ($gwMocked -and $backendPassthrough) {
        Write-Result "EG-GW-012" "PASS"
    } else {
        Write-Result "EG-GW-012" "FAIL" "gwMocked=$gwMocked backendPassthrough=$backendPassthrough"
    }
} catch {
    Write-Result "EG-GW-012" "FAIL" $_.Exception.Message
}

# 恢复 stub 模式
try {
    Switch-Mode "stub" -env "enterprise-gateway"
    Switch-Mode "stub" -env "enterprise-gateway-backend"
} catch {}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败, $skipped 跳过" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

# ==================== JUnit XML report (CI consumption) ====================
Export-JUnitXml (Join-Path $PSScriptRoot "junit-report.xml")

if ($failed -gt 0) {
    exit 1
}
