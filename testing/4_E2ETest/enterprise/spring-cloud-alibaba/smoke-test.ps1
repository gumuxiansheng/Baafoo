# Spring Cloud Alibaba 企业级测试 - 冒烟测试脚本
# 用法: .\smoke-test.ps1
#
# 验证 Baafoo Agent 在 Spring Cloud Alibaba 微服务架构下的：
# - 应用启动 + Agent 挂载
# - Agent 注册到 Server
# - Nacos 服务发现正常
# - Feign 调用 Mock 拦截（serviceName-based）
# - Passthrough 模式透传
# - Record 模式录制
# - 环境模式热切换
# - 应用功能完整性
# - 无类加载冲突

param(
    [string]$ServerBaseUrl = "http://localhost:18084",
    [string]$ProviderBaseUrl = "http://localhost:18081",
    [string]$ConsumerBaseUrl = "http://localhost:18083",
    [string]$ApiKey = "enterprise-admin-key"
)

$ErrorActionPreference = "Stop"
$passed = 0
$failed = 0
$skipped = 0
$MODE_SETTLE_WAIT = 12
$script:TestResults = @()   # JUnit XML source of truth

function Write-Result {
    param([string]$testName, [string]$status, [string]$detail = "")
    $statusLower = $status.ToLower()
    $script:TestResults += [PSCustomObject]@{ Name = $testName; Status = $statusLower; Message = $detail }
    switch ($status) {
        "PASS" {
            Write-Host "[PASS] $testName" -ForegroundColor Green
            $script:passed++
        }
        "FAIL" {
            Write-Host "[FAIL] $testName" -ForegroundColor Red
            if ($detail) { Write-Host "       $detail" -ForegroundColor DarkRed }
            $script:failed++
        }
        "SKIP" {
            Write-Host "[SKIP] $testName" -ForegroundColor Yellow
            if ($detail) { Write-Host "       $detail" -ForegroundColor DarkYellow }
            $script:skipped++
        }
    }
}

function Export-JUnitXml {
    param([string]$path)
    $ts = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    $sb = New-Object System.Text.StringBuilder
    $null = $sb.Append('<?xml version="1.0" encoding="UTF-8"?>')
    $null = $sb.AppendFormat('<testsuites name="baafoo-enterprise-sca" tests="{0}" failures="{1}" skipped="{2}" errors="0">', $script:TestResults.Count, $script:failed, $script:skipped)
    $null = $sb.AppendFormat('<testsuite name="EnterpriseSCA" tests="{0}" failures="{1}" skipped="{2}" errors="0" timestamp="{3}">', $script:TestResults.Count, $script:failed, $script:skipped, $ts)
    foreach ($t in $script:TestResults) {
        $nameEsc = [Security.SecurityElement]::Escape($t.Name)
        $msgEsc = [Security.SecurityElement]::Escape($t.Message)
        $null = $sb.AppendFormat('<testcase name="{0}" classname="EnterpriseSCA" status="{1}">', $nameEsc, $t.Status)
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

function Get-EnvId {
    param([string]$envName)
    try {
        $headers = @{ "X-Api-Key" = $script:ApiKey; "Content-Type" = "application/json" }
        $resp = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/environments" -Headers $headers -TimeoutSec 10
        $envs = @()
        if ($resp -is [array]) { $envs = $resp }
        elseif ($resp.data -is [array]) { $envs = $resp.data }
        elseif ($resp -is [PSCustomObject] -and $resp.environments) { $envs = $resp.environments }
        foreach ($e in $envs) {
            if ($e.name -eq $envName -or $e.id -eq $envName) { return $e.id }
        }
    } catch {}
    return $null
}

function Get-EnvMode {
    param([string]$envName)
    try {
        $headers = @{ "X-Api-Key" = $script:ApiKey; "Content-Type" = "application/json" }
        $resp = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/environments" -Headers $headers -TimeoutSec 10
        $envs = @()
        if ($resp -is [array]) { $envs = $resp }
        elseif ($resp.data -is [array]) { $envs = $resp.data }
        foreach ($e in $envs) {
            if ($e.name -eq $envName -or $e.id -eq $envName) { return $e.mode }
        }
    } catch {}
    return $null
}

function Switch-EnvMode {
    param([string]$envId, [string]$mode)
    $headers = @{ "X-Api-Key" = $script:ApiKey; "Content-Type" = "application/json" }
    $body = @{ mode = $mode } | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/environments/$envId" -Method Put `
        -ContentType "application/json" -Headers $headers -Body $body -TimeoutSec 10 | Out-Null
    Start-Sleep -Seconds $script:MODE_SETTLE_WAIT
}

function Restore-EnvMode {
    param([string]$envId, [string]$mode = "stub")
    if ($envId) {
        try { Switch-EnvMode $envId $mode } catch {}
    }
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Spring Cloud Alibaba 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server:   $ServerBaseUrl"
Write-Host "Provider: $ProviderBaseUrl"
Write-Host "Consumer: $ConsumerBaseUrl"
Write-Host ""

$apiKeyHeader = @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" }

# ========== EG-SCA-001: Provider 应用健康检查 ==========
try {
    $response = Invoke-WebRequest -Uri "$ProviderBaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 10
    $healthOk = $response.StatusCode -eq 200
    Write-Result "EG-SCA-001: Provider 应用健康检查" $(if ($healthOk) { "PASS" } else { "FAIL" }) "HTTP $($response.StatusCode)"
} catch {
    # Try /health endpoint as fallback
    try {
        $response2 = Invoke-WebRequest -Uri "$ProviderBaseUrl/health" -UseBasicParsing -TimeoutSec 10
        Write-Result "EG-SCA-001: Provider 应用健康检查" $(if ($response2.StatusCode -eq 200) { "PASS" } else { "FAIL" }) "HTTP $($response2.StatusCode)"
    } catch {
        Write-Result "EG-SCA-001: Provider 应用健康检查" "FAIL" $_.Exception.Message
    }
}

# ========== EG-SCA-002: Consumer 应用健康检查 ==========
try {
    $response = Invoke-WebRequest -Uri "$ConsumerBaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 10
    $healthOk = $response.StatusCode -eq 200
    Write-Result "EG-SCA-002: Consumer 应用健康检查" $(if ($healthOk) { "PASS" } else { "FAIL" }) "HTTP $($response.StatusCode)"
} catch {
    try {
        $response2 = Invoke-WebRequest -Uri "$ConsumerBaseUrl/health" -UseBasicParsing -TimeoutSec 10
        Write-Result "EG-SCA-002: Consumer 应用健康检查" $(if ($response2.StatusCode -eq 200) { "PASS" } else { "FAIL" }) "HTTP $($response2.StatusCode)"
    } catch {
        Write-Result "EG-SCA-002: Consumer 应用健康检查" "FAIL" $_.Exception.Message
    }
}

# ========== EG-SCA-003: Provider Agent 注册验证 ==========
try {
    $response = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentData = @()
    if ($response -is [array]) { $agentData = $response }
    elseif ($response.data -is [array]) { $agentData = $response.data }
    elseif ($response.agents -is [array]) { $agentData = $response.agents }
    $providerAgent = $agentData | Where-Object { $_.environment -eq "enterprise-sca-provider" -and $_.status -eq "online" }
    $agentFound = $providerAgent.Count -gt 0
    Write-Result "EG-SCA-003: Provider Agent 注册验证" $(if ($agentFound) { "PASS" } else { "FAIL" }) "未找到 environment=enterprise-sca-provider 的 online agent"
} catch {
    Write-Result "EG-SCA-003: Provider Agent 注册验证" "FAIL" $_.Exception.Message
}

# ========== EG-SCA-004: Consumer Agent 注册验证 ==========
try {
    $response = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentData = @()
    if ($response -is [array]) { $agentData = $response }
    elseif ($response.data -is [array]) { $agentData = $response.data }
    elseif ($response.agents -is [array]) { $agentData = $response.agents }
    $consumerAgent = $agentData | Where-Object { $_.environment -eq "enterprise-sca-consumer" -and $_.status -eq "online" }
    $agentFound = $consumerAgent.Count -gt 0
    Write-Result "EG-SCA-004: Consumer Agent 注册验证" $(if ($agentFound) { "PASS" } else { "FAIL" }) "未找到 environment=enterprise-sca-consumer 的 online agent"
} catch {
    Write-Result "EG-SCA-004: Consumer Agent 注册验证" "FAIL" $_.Exception.Message
}

# ========== EG-SCA-005: Nacos 服务发现验证 ==========
try {
    # Consumer should see service-provider in Nacos discovery
    $response = Invoke-RestMethod -Uri "$ConsumerBaseUrl/services" -UseBasicParsing -TimeoutSec 10
    $servicesJson = $response | ConvertTo-Json -Depth 5
    $providerDiscovered = $servicesJson -match "service-provider"
    Write-Result "EG-SCA-005: Nacos 服务发现验证" $(if ($providerDiscovered) { "PASS" } else { "FAIL" }) "service-provider not in discovered services: $servicesJson"
} catch {
    Write-Result "EG-SCA-005: Nacos 服务发现验证" "FAIL" $_.Exception.Message
}

# ========== EG-SCA-006: Feign 调用 Mock 拦截 (STUB 模式) ==========
$consumerEnvId = Get-EnvId "enterprise-sca-consumer"
$origMode = Get-EnvMode "enterprise-sca-consumer"
try {
    if ($consumerEnvId -and $origMode -ne "stub") {
        Switch-EnvMode $consumerEnvId "stub"
    }

    # Call consumer's feign endpoint — should be mocked by Baafoo
    $response = Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/hello" -UseBasicParsing -TimeoutSec 15
    $respStr = "$response"
    # Mock rule returns "hello Nacos Discovery mock"
    $mocked = $respStr -match "mock"
    Write-Result "EG-SCA-006: Feign 调用 Mock 拦截" $(if ($mocked) { "PASS" } else { "FAIL" }) "response: $respStr"
} catch {
    Write-Result "EG-SCA-006: Feign 调用 Mock 拦截" "FAIL" $_.Exception.Message
}

# ========== EG-SCA-007: Passthrough 模式透传 ==========
try {
    if ($consumerEnvId) {
        Switch-EnvMode $consumerEnvId "passthrough"
        # In passthrough, Feign call should reach real provider
        $response = Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/hello" -UseBasicParsing -TimeoutSec 15
        $respStr = "$response"
        # Real provider returns "hello Nacos Discovery hello"
        $realResponse = $respStr -match "hello Nacos Discovery" -and $respStr -notmatch "mock"
        Write-Result "EG-SCA-007: Passthrough 模式透传" $(if ($realResponse) { "PASS" } else { "FAIL" }) "response: $respStr"
    } else {
        Write-Result "EG-SCA-007: Passthrough 模式透传" "SKIP" "环境 enterprise-sca-consumer 未找到"
    }
} catch {
    Write-Result "EG-SCA-007: Passthrough 模式透传" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $consumerEnvId $origMode
}

# ========== EG-SCA-008: Record 模式录制 ==========
try {
    if ($consumerEnvId) {
        $recBefore = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/recordings?limit=50" -Headers $apiKeyHeader -TimeoutSec 10
        $recBeforeList = @()
        if ($recBefore -is [array]) { $recBeforeList = $recBefore }
        elseif ($recBefore.data -is [array]) { $recBeforeList = $recBefore.data }
        elseif ($recBefore.recordings -is [array]) { $recBeforeList = $recBefore.recordings }
        $recBeforeCount = $recBeforeList.Count

        Switch-EnvMode $consumerEnvId "record"

        # Generate traffic via Feign
        Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/record-test" -UseBasicParsing -TimeoutSec 15 | Out-Null
        Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/record-test-2" -UseBasicParsing -TimeoutSec 15 | Out-Null
        Start-Sleep -Seconds 2

        $recAfter = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/recordings?limit=50" -Headers $apiKeyHeader -TimeoutSec 10
        $recAfterList = @()
        if ($recAfter -is [array]) { $recAfterList = $recAfter }
        elseif ($recAfter.data -is [array]) { $recAfterList = $recAfter.data }
        elseif ($recAfter.recordings -is [array]) { $recAfterList = $recAfter.recordings }
        $recAfterCount = $recAfterList.Count

        $recorded = $recAfterCount -gt $recBeforeCount
        Write-Result "EG-SCA-008: Record 模式录制" $(if ($recorded) { "PASS" } else { "FAIL" }) "recordings: $recBeforeCount -> $recAfterCount"
    } else {
        Write-Result "EG-SCA-008: Record 模式录制" "SKIP" "环境 enterprise-sca-consumer 未找到"
    }
} catch {
    Write-Result "EG-SCA-008: Record 模式录制" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $consumerEnvId $origMode
}

# ========== EG-SCA-009: 环境模式热切换 ==========
try {
    if ($consumerEnvId) {
        # stub -> mock
        Switch-EnvMode $consumerEnvId "stub"
        $stubResp = Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/hotswap" -UseBasicParsing -TimeoutSec 15
        $stubStr = "$stubResp"
        $stubWorks = $stubStr -match "mock"

        # passthrough -> real
        Switch-EnvMode $consumerEnvId "passthrough"
        $ptResp = Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/hotswap" -UseBasicParsing -TimeoutSec 15
        $ptStr = "$ptResp"
        $ptWorks = $ptStr -match "hello Nacos Discovery" -and $ptStr -notmatch "mock"

        # stub back
        Switch-EnvMode $consumerEnvId "stub"
        $stubAgainResp = Invoke-RestMethod -Uri "$ConsumerBaseUrl/echo-feign/hotswap2" -UseBasicParsing -TimeoutSec 15
        $stubAgainStr = "$stubAgainResp"
        $stubRestored = $stubAgainStr -match "mock"

        $hotswapOk = $stubWorks -and $ptWorks -and $stubRestored
        Write-Result "EG-SCA-009: 环境模式热切换" $(if ($hotswapOk) { "PASS" } else { "FAIL" }) "stub=$stubWorks pt=$ptWorks restored=$stubRestored"
    } else {
        Write-Result "EG-SCA-009: 环境模式热切换" "SKIP" "环境 enterprise-sca-consumer 未找到"
    }
} catch {
    Write-Result "EG-SCA-009: 环境模式热切换" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $consumerEnvId $origMode
}

# ========== EG-SCA-010: Provider 直接调用功能完整性 ==========
try {
    # Test provider's echo and divide endpoints directly (no Agent interception on provider itself)
    $echoResp = Invoke-RestMethod -Uri "$ProviderBaseUrl/echo/direct-test" -UseBasicParsing -TimeoutSec 10
    $echoOk = "$echoResp" -match "hello Nacos Discovery direct-test"

    $divideResp = Invoke-RestMethod -Uri "$ProviderBaseUrl/divide?a=10&b=2" -UseBasicParsing -TimeoutSec 10
    $divideOk = "$divideResp" -match "5"

    $allOk = $echoOk -and $divideOk
    Write-Result "EG-SCA-010: Provider 功能完整性" $(if ($allOk) { "PASS" } else { "FAIL" }) "echo=$echoOk divide=$divideOk"
} catch {
    Write-Result "EG-SCA-010: Provider 功能完整性" "FAIL" $_.Exception.Message
}

# ========== EG-SCA-011: Feign divide 调用验证 ==========
try {
    if ($consumerEnvId) { Switch-EnvMode $consumerEnvId "passthrough" }
    $response = Invoke-RestMethod -Uri "$ConsumerBaseUrl/divide-feign?a=20&b=4" -UseBasicParsing -TimeoutSec 15
    $respStr = "$response"
    $divideOk = $respStr -match "5"
    Write-Result "EG-SCA-011: Feign divide 调用验证" $(if ($divideOk) { "PASS" } else { "FAIL" }) "20/4 = $respStr (expected 5)"
} catch {
    Write-Result "EG-SCA-011: Feign divide 调用验证" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $consumerEnvId $origMode
}

# ========== EG-SCA-012: 无类加载冲突 ==========
try {
    $agentsResp = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentList = @()
    if ($agentsResp -is [array]) { $agentList = $agentsResp }
    elseif ($agentsResp.data -is [array]) { $agentList = $agentsResp.data }
    elseif ($agentsResp.agents -is [array]) { $agentList = $agentsResp.agents }

    $scaAgents = $agentList | Where-Object { $_.environment -match "enterprise-sca" }
    $noClassLoadError = $true
    $errorDetail = ""
    foreach ($agent in $scaAgents) {
        $pluginStatuses = $agent.pluginStatuses
        if ($pluginStatuses) {
            $errorPlugins = $pluginStatuses | Where-Object { $_.status -eq "ERROR" -or $_.state -eq "ERROR" }
            if ($errorPlugins) {
                $noClassLoadError = $false
                $errorDetail += ($errorPlugins | ForEach-Object { "$($agent.environment): $($_.name): $($_.error)" }) -join "; "
            }
        }
        if ($agent.errors -or $agent.lastError) {
            $errStr = "$($agent.errors) $($agent.lastError)"
            if ($errStr -match "ClassNotFoundException|NoClassDefFoundError|LinkageError|ClassCastException") {
                $noClassLoadError = $false
                $errorDetail += "$($agent.environment): $errStr"
            }
        }
    }
    Write-Result "EG-SCA-012: 无类加载冲突" $(if ($noClassLoadError) { "PASS" } else { "FAIL" }) $errorDetail
} catch {
    Write-Result "EG-SCA-012: 无类加载冲突" "FAIL" $_.Exception.Message
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败, $skipped 跳过" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

# ==================== JUnit XML report (CI consumption) ====================
Export-JUnitXml (Join-Path $PSScriptRoot "junit-report.xml")

if ($failed -gt 0) {
    exit 1
}
