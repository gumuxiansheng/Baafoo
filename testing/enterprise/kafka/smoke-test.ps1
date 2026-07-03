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

function Write-Result {
    param([string]$testName, [bool]$success, [string]$detail = "")
    if ($success) {
        Write-Host "[PASS] $testName" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host "[FAIL] $testName" -ForegroundColor Red
        if ($detail) { Write-Host "       $detail" -ForegroundColor DarkRed }
        $script:failed++
    }
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Kafka 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $ServerBaseUrl"
Write-Host "App:    $AppBaseUrl"
Write-Host ""

# EG-KAFKA-001: 应用健康检查
try {
    $response = Invoke-WebRequest -Uri "$AppBaseUrl/api/stub-demo/health" -UseBasicParsing -TimeoutSec 10
    Write-Result "EG-KAFKA-001: 应用启动健康检查" ($response.StatusCode -eq 200)
} catch {
    Write-Result "EG-KAFKA-001: 应用启动健康检查" $false $_.Exception.Message
}

# EG-KAFKA-002: Agent 注册验证
try {
    $headers = @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" }
    $response = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $headers -TimeoutSec 10
    $agentFound = $false
    if ($response -is [array]) {
        $agentFound = ($response | Where-Object { $_.environment -eq "enterprise-kafka" -and $_.status -eq "online" }).Count -gt 0
    } elseif ($response.data -is [array]) {
        $agentFound = ($response.data | Where-Object { $_.environment -eq "enterprise-kafka" -and $_.status -eq "online" }).Count -gt 0
    }
    Write-Result "EG-KAFKA-002: Agent 成功注册" $agentFound
} catch {
    Write-Result "EG-KAFKA-002: Agent 成功注册" $false $_.Exception.Message
}

# EG-KAFKA-003: Producer 消息发送 Mock
# 注意: baafoo-test-spring 的 KafkaCallerController 使用 GET 方法
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-test-topic&message=hello-enterprise-smoke-test" -UseBasicParsing -TimeoutSec 15
    Write-Result "EG-KAFKA-003: Kafka Producer Mock" ($response.success -eq $true)
} catch {
    Write-Result "EG-KAFKA-003: Kafka Producer Mock" $false $_.Exception.Message
}

# EG-KAFKA-004: Consumer 消息消费 Mock
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/api/kafka/consume?bootstrapServers=kafka:9092&topic=enterprise-test-topic" -UseBasicParsing -TimeoutSec 15
    Write-Result "EG-KAFKA-004: Kafka Consumer Mock" ($response.success -eq $true)
} catch {
    Write-Result "EG-KAFKA-004: Kafka Consumer Mock" $false $_.Exception.Message
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

if ($failed -gt 0) {
    exit 1
}
