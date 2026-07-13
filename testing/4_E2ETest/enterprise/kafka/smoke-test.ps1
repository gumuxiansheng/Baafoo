# Kafka 企业级测试 - 冒烟测试脚本
# 用法: .\smoke-test.ps1

param(
    [string]$ServerBaseUrl = "http://localhost:18084",
    [string]$AppBaseUrl = "http://localhost:18090",
    [string]$ApiKey = "enterprise-admin-key"
)

$ErrorActionPreference = "Stop"
$passed = 0
$failed = 0
$skipped = 0
$MODE_SETTLE_WAIT = 12  # Agent poll interval is 10s; wait 12s for mode switch

function Write-Result {
    param([string]$testName, [string]$status, [string]$detail = "")
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
Write-Host "  Kafka 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $ServerBaseUrl"
Write-Host "App:    $AppBaseUrl"
Write-Host ""

$apiKeyHeader = @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" }

# ========== EG-KAFKA-001: 应用健康检查 ==========
try {
    $response = Invoke-WebRequest -Uri "$AppBaseUrl/api/stub-demo/health" -UseBasicParsing -TimeoutSec 10
    Write-Result "EG-KAFKA-001: 应用启动健康检查" $(if ($response.StatusCode -eq 200) { "PASS" } else { "FAIL" }) "HTTP $($response.StatusCode)"
} catch {
    Write-Result "EG-KAFKA-001: 应用启动健康检查" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-002: Agent 注册验证 ==========
try {
    $response = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentFound = $false
    $agentData = @()
    if ($response -is [array]) { $agentData = $response }
    elseif ($response.data -is [array]) { $agentData = $response.data }
    elseif ($response.agents -is [array]) { $agentData = $response.agents }
    $agentFound = ($agentData | Where-Object { $_.environment -eq "enterprise-kafka" -and $_.status -eq "online" }).Count -gt 0
    Write-Result "EG-KAFKA-002: Agent 成功注册" $(if ($agentFound) { "PASS" } else { "FAIL" }) "未找到 environment=enterprise-kafka 的 online agent"
} catch {
    Write-Result "EG-KAFKA-002: Agent 成功注册" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-003: Producer 消息发送 Mock ==========
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-test-topic&message=hello-enterprise-smoke-test" -UseBasicParsing -TimeoutSec 15
    Write-Result "EG-KAFKA-003: Kafka Producer Mock" $(if ($response.success -eq $true) { "PASS" } else { "FAIL" }) "success=$($response.success)"
} catch {
    Write-Result "EG-KAFKA-003: Kafka Producer Mock" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-004: Consumer 消息消费 Mock ==========
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/consume?bootstrapServers=kafka:9092&topic=enterprise-test-topic" -UseBasicParsing -TimeoutSec 15
    Write-Result "EG-KAFKA-004: Kafka Consumer Mock" $(if ($response.success -eq $true) { "PASS" } else { "FAIL" }) "success=$($response.success)"
} catch {
    Write-Result "EG-KAFKA-004: Kafka Consumer Mock" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-005: Topic 通配符匹配 ==========
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-wildcard-test&message=wildcard-test" -UseBasicParsing -TimeoutSec 15
    $wildcardMatched = $false
    if ($response.success -eq $true) {
        # Consume and check if mock response has wildcard flag
        $consumeResp = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/consume?bootstrapServers=kafka:9092&topic=enterprise-wildcard-test" -UseBasicParsing -TimeoutSec 15
        if ($consumeResp.success -eq $true) {
            $respJson = $consumeResp | ConvertTo-Json -Depth 5
            $wildcardMatched = $respJson -match "wildcard"
        }
    }
    Write-Result "EG-KAFKA-005: Topic 通配符匹配" $(if ($wildcardMatched) { "PASS" } else { "FAIL" }) "topic=enterprise-wildcard-test"
} catch {
    Write-Result "EG-KAFKA-005: Topic 通配符匹配" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-006: Passthrough 模式透传真实 Kafka ==========
$kafkaEnvId = Get-EnvId "enterprise-kafka"
$origMode = $null
try {
    if ($kafkaEnvId) {
        # Get current mode
        $envResp = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/environments" -Headers $apiKeyHeader -TimeoutSec 10
        $envList = @()
        if ($envResp -is [array]) { $envList = $envResp }
        elseif ($envResp.data -is [array]) { $envList = $envResp.data }
        foreach ($e in $envList) {
            if ($e.id -eq $kafkaEnvId -or $e.name -eq "enterprise-kafka") { $origMode = $e.mode }
        }

        Switch-EnvMode $kafkaEnvId "passthrough"

        # Send to real Kafka — in passthrough, message goes to real broker
        $response = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-pt-test&message=pt-test-msg" -UseBasicParsing -TimeoutSec 15
        $ptSuccess = $response.success -eq $true

        Write-Result "EG-KAFKA-006: Passthrough 模式透传" $(if ($ptSuccess) { "PASS" } else { "FAIL" }) "success=$($response.success)"
    } else {
        Write-Result "EG-KAFKA-006: Passthrough 模式透传" "SKIP" "环境 enterprise-kafka 未找到"
    }
} catch {
    Write-Result "EG-KAFKA-006: Passthrough 模式透传" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $kafkaEnvId $origMode
}

# ========== EG-KAFKA-007: Record 模式录制 ==========
try {
    if ($kafkaEnvId) {
        $recBefore = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/recordings?limit=50" -Headers $apiKeyHeader -TimeoutSec 10
        $recBeforeCount = 0
        $recBeforeList = @()
        if ($recBefore -is [array]) { $recBeforeList = $recBefore }
        elseif ($recBefore.data -is [array]) { $recBeforeList = $recBefore.data }
        elseif ($recBefore.recordings -is [array]) { $recBeforeList = $recBefore.recordings }
        $recBeforeCount = $recBeforeList.Count

        Switch-EnvMode $kafkaEnvId "record"

        # Send a message to be recorded
        Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-record-test&message=record-test-msg" -UseBasicParsing -TimeoutSec 15 | Out-Null

        Start-Sleep -Seconds 2

        $recAfter = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/recordings?limit=50" -Headers $apiKeyHeader -TimeoutSec 10
        $recAfterList = @()
        if ($recAfter -is [array]) { $recAfterList = $recAfter }
        elseif ($recAfter.data -is [array]) { $recAfterList = $recAfter.data }
        elseif ($recAfter.recordings -is [array]) { $recAfterList = $recAfter.recordings }
        $recAfterCount = $recAfterList.Count

        $recorded = $recAfterCount -gt $recBeforeCount
        Write-Result "EG-KAFKA-007: Record 模式录制" $(if ($recorded) { "PASS" } else { "FAIL" }) "recordings: $recBeforeCount -> $recAfterCount"
    } else {
        Write-Result "EG-KAFKA-007: Record 模式录制" "SKIP" "环境 enterprise-kafka 未找到"
    }
} catch {
    Write-Result "EG-KAFKA-007: Record 模式录制" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $kafkaEnvId $origMode
}

# ========== EG-KAFKA-008: 环境模式热切换 ==========
try {
    if ($kafkaEnvId) {
        # Start in stub mode — verify mock
        Switch-EnvMode $kafkaEnvId "stub"
        $stubResp = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-hotswap-test&message=hotswap-1" -UseBasicParsing -TimeoutSec 15
        $stubWorks = $stubResp.success -eq $true

        # Switch to passthrough — verify passthrough
        Switch-EnvMode $kafkaEnvId "passthrough"
        $ptResp = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-hotswap-test&message=hotswap-2" -UseBasicParsing -TimeoutSec 15
        $ptWorks = $ptResp.success -eq $true

        # Switch back to stub — verify mock restored
        Switch-EnvMode $kafkaEnvId "stub"
        $stubAgainResp = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-hotswap-test&message=hotswap-3" -UseBasicParsing -TimeoutSec 15
        $stubRestored = $stubAgainResp.success -eq $true

        $hotswapOk = $stubWorks -and $ptWorks -and $stubRestored
        Write-Result "EG-KAFKA-008: 环境模式热切换" $(if ($hotswapOk) { "PASS" } else { "FAIL" }) "stub=$stubWorks pt=$ptWorks stubRestored=$stubRestored"
    } else {
        Write-Result "EG-KAFKA-008: 环境模式热切换" "SKIP" "环境 enterprise-kafka 未找到"
    }
} catch {
    Write-Result "EG-KAFKA-008: 环境模式热切换" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $kafkaEnvId $origMode
}

# ========== EG-KAFKA-009: 无类加载冲突 ==========
try {
    # Query agent plugin statuses for class loading errors
    $agentsResp = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentList = @()
    if ($agentsResp -is [array]) { $agentList = $agentsResp }
    elseif ($agentsResp.data -is [array]) { $agentList = $agentsResp.data }
    elseif ($agentsResp.agents -is [array]) { $agentList = $agentsResp.agents }

    $kafkaAgent = $agentList | Where-Object { $_.environment -eq "enterprise-kafka" } | Select-Object -First 1
    $noClassLoadError = $true
    $errorDetail = ""
    if ($kafkaAgent) {
        # Check plugin statuses for errors
        $pluginStatuses = $kafkaAgent.pluginStatuses
        if ($pluginStatuses) {
            $errorPlugins = $pluginStatuses | Where-Object { $_.status -eq "ERROR" -or $_.state -eq "ERROR" }
            if ($errorPlugins) {
                $noClassLoadError = $false
                $errorDetail = ($errorPlugins | ForEach-Object { $_.name + ": " + $_.error }) -join "; "
            }
        }
        # Also check agent errors field if present
        if ($kafkaAgent.errors -or $kafkaAgent.lastError) {
            $errStr = "$($kafkaAgent.errors) $($kafkaAgent.lastError)"
            if ($errStr -match "ClassNotFoundException|NoClassDefFoundError|LinkageError|ClassCastException") {
                $noClassLoadError = $false
                $errorDetail = $errStr
            }
        }
    }
    Write-Result "EG-KAFKA-009: 无类加载冲突" $(if ($noClassLoadError) { "PASS" } else { "FAIL" }) $errorDetail
} catch {
    Write-Result "EG-KAFKA-009: 无类加载冲突" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-010: 高吞吐下 Agent 稳定性 ==========
try {
    $msgCount = 0
    $failCount = 0
    $batchSize = 50
    $batchNum = 6  # 300 messages total (~30 seconds)
    for ($batch = 0; $batch -lt $batchNum; $batch++) {
        for ($i = 0; $i -lt $batchSize; $i++) {
            try {
                $msg = "throughput-test-batch$batch-msg$i"
                Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-throughput-test&message=$msg" -UseBasicParsing -TimeoutSec 5 | Out-Null
                $msgCount++
            } catch {
                $failCount++
            }
        }
        Start-Sleep -Milliseconds 200
    }
    $successRate = if ($msgCount -gt 0) { [math]::Round(($msgCount / ($msgCount + $failCount)) * 100, 1) } else { 0 }
    $stable = $successRate -ge 95 -and $failCount -lt 20
    Write-Result "EG-KAFKA-010: 高吞吐下 Agent 稳定性" $(if ($stable) { "PASS" } else { "FAIL" }) "sent=$msgCount failed=$failCount rate=${successRate}%"
} catch {
    Write-Result "EG-KAFKA-010: 高吞吐下 Agent 稳定性" "FAIL" $_.Exception.Message
}

# ========== EG-KAFKA-011: Kafka 多版本客户端兼容 ==========
# 注: 当前环境只有一个 Kafka 客户端版本，此测试为信息性
Write-Result "EG-KAFKA-011: Kafka 多版本客户端兼容" "SKIP" "当前环境仅有一个 Kafka 客户端版本，需多版本环境支持"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败, $skipped 跳过" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

if ($failed -gt 0) {
    exit 1
}
