# Spring Boot PetClinic 企业级测试 - 冒烟测试脚本
# 用法: .\smoke-test.ps1

param(
    [string]$ServerBaseUrl = "http://localhost:18084",
    [string]$AppBaseUrl = "http://localhost:19966",
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
Write-Host "  PetClinic 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $ServerBaseUrl"
Write-Host "App:    $AppBaseUrl"
Write-Host ""

# EG-PET-001: 应用健康检查
try {
    $response = Invoke-WebRequest -Uri "$AppBaseUrl/petclinic/api/vets" -UseBasicParsing -TimeoutSec 10
    Write-Result "EG-PET-001: 应用启动健康检查" ($response.StatusCode -eq 200)
} catch {
    Write-Result "EG-PET-001: 应用启动健康检查" $false $_.Exception.Message
}

# EG-PET-002: Agent 注册验证
try {
    $headers = @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" }
    $response = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $headers -TimeoutSec 10
    $agentFound = $false
    if ($response -is [array]) {
        $agentFound = ($response | Where-Object { $_.environment -eq "enterprise-petclinic" -and $_.status -eq "online" }).Count -gt 0
    } elseif ($response.data -is [array]) {
        $agentFound = ($response.data | Where-Object { $_.environment -eq "enterprise-petclinic" -and $_.status -eq "online" }).Count -gt 0
    }
    Write-Result "EG-PET-002: Agent 成功注册" $agentFound
} catch {
    Write-Result "EG-PET-002: Agent 成功注册" $false $_.Exception.Message
}

# EG-PET-003: Vet API Mock 验证
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/petclinic/api/vets" -UseBasicParsing -TimeoutSec 10
    $hasMocked = $false
    if ($response -is [PSCustomObject]) {
        $hasMocked = $response.PSObject.Properties.Name -contains "mocked" -and $response.mocked -eq $true
    }
    Write-Result "EG-PET-003: Vet API Mock 验证" $hasMocked
} catch {
    Write-Result "EG-PET-003: Vet API Mock 验证" $false $_.Exception.Message
}

# EG-PET-008: 应用功能完整性（passthrough 模式需要）
# 这里只验证 API 可访问，不验证内容
try {
    $response = Invoke-WebRequest -Uri "$AppBaseUrl/petclinic/api/specialties" -UseBasicParsing -TimeoutSec 10
    Write-Result "EG-PET-008a: 专科列表API可访问" ($response.StatusCode -eq 200)
} catch {
    Write-Result "EG-PET-008a: 专科列表API可访问" $false $_.Exception.Message
}

try {
    $response = Invoke-WebRequest -Uri "$AppBaseUrl/petclinic/api/pettypes" -UseBasicParsing -TimeoutSec 10
    Write-Result "EG-PET-008b: 宠物类型API可访问" ($response.StatusCode -eq 200)
} catch {
    Write-Result "EG-PET-008b: 宠物类型API可访问" $false $_.Exception.Message
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

if ($failed -gt 0) {
    exit 1
}
