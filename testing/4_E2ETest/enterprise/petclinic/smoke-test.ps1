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
    $null = $sb.AppendFormat('<testsuites name="baafoo-enterprise-petclinic" tests="{0}" failures="{1}" skipped="{2}" errors="0">', $script:TestResults.Count, $script:failed, $script:skipped)
    $null = $sb.AppendFormat('<testsuite name="EnterprisePetClinic" tests="{0}" failures="{1}" skipped="{2}" errors="0" timestamp="{3}">', $script:TestResults.Count, $script:failed, $script:skipped, $ts)
    foreach ($t in $script:TestResults) {
        $nameEsc = [Security.SecurityElement]::Escape($t.Name)
        $msgEsc = [Security.SecurityElement]::Escape($t.Message)
        $null = $sb.AppendFormat('<testcase name="{0}" classname="EnterprisePetClinic" status="{1}">', $nameEsc, $t.Status)
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
Write-Host "  PetClinic 企业级测试 - 冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $ServerBaseUrl"
Write-Host "App:    $AppBaseUrl"
Write-Host ""

$apiKeyHeader = @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" }
$petEnvId = Get-EnvId "enterprise-petclinic"
$origMode = Get-EnvMode "enterprise-petclinic"

# ========== EG-PET-001: 应用健康检查 ==========
try {
    $response = Invoke-WebRequest -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10
    Write-Result "EG-PET-001: 应用启动健康检查" $(if ($response.StatusCode -eq 200) { "PASS" } else { "FAIL" }) "HTTP $($response.StatusCode)"
} catch {
    Write-Result "EG-PET-001: 应用启动健康检查" "FAIL" $_.Exception.Message
}

# ========== EG-PET-002: Agent 注册验证 ==========
try {
    $response = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentData = @()
    if ($response -is [array]) { $agentData = $response }
    elseif ($response.data -is [array]) { $agentData = $response.data }
    elseif ($response.agents -is [array]) { $agentData = $response.agents }
    $agentFound = ($agentData | Where-Object { $_.environment -eq "enterprise-petclinic" -and $_.status -eq "online" }).Count -gt 0
    Write-Result "EG-PET-002: Agent 成功注册" $(if ($agentFound) { "PASS" } else { "FAIL" }) "未找到 environment=enterprise-petclinic 的 online agent"
} catch {
    Write-Result "EG-PET-002: Agent 成功注册" "FAIL" $_.Exception.Message
}

# ========== EG-PET-003: Vet API Mock 验证 ==========
try {
    # Ensure stub mode
    if ($petEnvId -and $origMode -ne "stub") { Switch-EnvMode $petEnvId "stub" }
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10
    $hasMocked = $false
    if ($response -is [PSCustomObject]) {
        $hasMocked = $response.PSObject.Properties.Name -contains "mocked" -and $response.mocked -eq $true
    } elseif ($response -is [string]) {
        $hasMocked = $response -match "mocked"
    }
    Write-Result "EG-PET-003: Vet API Mock 验证" $(if ($hasMocked) { "PASS" } else { "FAIL" }) "mocked flag not found"
} catch {
    Write-Result "EG-PET-003: Vet API Mock 验证" "FAIL" $_.Exception.Message
}

# ========== EG-PET-004: Owner API Mock 验证 ==========
try {
    $response = Invoke-RestMethod -Uri "$AppBaseUrl/owners" -UseBasicParsing -TimeoutSec 10
    $hasMocked = $false
    $respJson = $response | ConvertTo-Json -Depth 5
    $hasMocked = $respJson -match "mocked"
    Write-Result "EG-PET-004: Owner API Mock 验证" $(if ($hasMocked) { "PASS" } else { "FAIL" }) "mocked flag not found in owners response"
} catch {
    Write-Result "EG-PET-004: Owner API Mock 验证" "FAIL" $_.Exception.Message
}

# ========== EG-PET-005: Passthrough 模式透传验证 ==========
try {
    if ($petEnvId) {
        Switch-EnvMode $petEnvId "passthrough"
        $response = Invoke-RestMethod -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10
        $respJson = $response | ConvertTo-Json -Depth 5
        $notMocked = $respJson -notmatch '"mocked"\s*:\s*true'
        Write-Result "EG-PET-005: Passthrough 模式透传" $(if ($notMocked) { "PASS" } else { "FAIL" }) "response still contains mocked flag"
    } else {
        Write-Result "EG-PET-005: Passthrough 模式透传" "SKIP" "环境 enterprise-petclinic 未找到"
    }
} catch {
    Write-Result "EG-PET-005: Passthrough 模式透传" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $petEnvId $origMode
}

# ========== EG-PET-006: Record 模式录制验证 ==========
try {
    if ($petEnvId) {
        $recBefore = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/recordings?limit=50" -Headers $apiKeyHeader -TimeoutSec 10
        $recBeforeList = @()
        if ($recBefore -is [array]) { $recBeforeList = $recBefore }
        elseif ($recBefore.data -is [array]) { $recBeforeList = $recBefore.data }
        elseif ($recBefore.recordings -is [array]) { $recBeforeList = $recBefore.recordings }
        $recBeforeCount = $recBeforeList.Count

        Switch-EnvMode $petEnvId "record"

        # Make several API calls to generate traffic
        Invoke-RestMethod -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10 | Out-Null
        Invoke-RestMethod -Uri "$AppBaseUrl/owners" -UseBasicParsing -TimeoutSec 10 | Out-Null
        Start-Sleep -Seconds 2

        $recAfter = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/recordings?limit=50" -Headers $apiKeyHeader -TimeoutSec 10
        $recAfterList = @()
        if ($recAfter -is [array]) { $recAfterList = $recAfter }
        elseif ($recAfter.data -is [array]) { $recAfterList = $recAfter.data }
        elseif ($recAfter.recordings -is [array]) { $recAfterList = $recAfter.recordings }
        $recAfterCount = $recAfterList.Count

        $recorded = $recAfterCount -gt $recBeforeCount
        Write-Result "EG-PET-006: Record 模式录制" $(if ($recorded) { "PASS" } else { "FAIL" }) "recordings: $recBeforeCount -> $recAfterCount"
    } else {
        Write-Result "EG-PET-006: Record 模式录制" "SKIP" "环境 enterprise-petclinic 未找到"
    }
} catch {
    Write-Result "EG-PET-006: Record 模式录制" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $petEnvId $origMode
}

# ========== EG-PET-007: 环境模式热切换 ==========
try {
    if ($petEnvId) {
        # stub -> verify mock
        Switch-EnvMode $petEnvId "stub"
        $stubResp = Invoke-RestMethod -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10
        $stubJson = $stubResp | ConvertTo-Json -Depth 5
        $stubWorks = $stubJson -match "mocked"

        # passthrough -> verify real
        Switch-EnvMode $petEnvId "passthrough"
        $ptResp = Invoke-RestMethod -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10
        $ptJson = $ptResp | ConvertTo-Json -Depth 5
        $ptWorks = $ptJson -notmatch '"mocked"\s*:\s*true'

        # stub back -> verify mock restored
        Switch-EnvMode $petEnvId "stub"
        $stubAgainResp = Invoke-RestMethod -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10
        $stubAgainJson = $stubAgainResp | ConvertTo-Json -Depth 5
        $stubRestored = $stubAgainJson -match "mocked"

        $hotswapOk = $stubWorks -and $ptWorks -and $stubRestored
        Write-Result "EG-PET-007: 环境模式热切换" $(if ($hotswapOk) { "PASS" } else { "FAIL" }) "stub=$stubWorks pt=$ptWorks restored=$stubRestored"
    } else {
        Write-Result "EG-PET-007: 环境模式热切换" "SKIP" "环境 enterprise-petclinic 未找到"
    }
} catch {
    Write-Result "EG-PET-007: 环境模式热切换" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $petEnvId $origMode
}

# ========== EG-PET-008: 应用功能完整性验证 ==========
try {
    # Ensure passthrough mode for real API testing
    if ($petEnvId) { Switch-EnvMode $petEnvId "passthrough" }

    $endpoints = @("/vets", "/owners", "/pets", "/specialties", "/visits")
    $allOk = $true
    $failedEndpoints = @()
    foreach ($ep in $endpoints) {
        try {
            $resp = Invoke-WebRequest -Uri "$AppBaseUrl$ep" -UseBasicParsing -TimeoutSec 10
            if ($resp.StatusCode -ne 200) {
                $allOk = $false
                $failedEndpoints += "$ep($($resp.StatusCode))"
            }
        } catch {
            $allOk = $false
            $failedEndpoints += "$ep(err)"
        }
    }
    $detail = if ($failedEndpoints.Count -gt 0) { "Failed: $($failedEndpoints -join ', ')" } else { "All 5 endpoints OK" }
    Write-Result "EG-PET-008: 应用功能完整性验证" $(if ($allOk) { "PASS" } else { "FAIL" }) $detail
} catch {
    Write-Result "EG-PET-008: 应用功能完整性验证" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $petEnvId $origMode
}

# ========== EG-PET-009: 无类加载冲突 ==========
try {
    $agentsResp = Invoke-RestMethod -Uri "$ServerBaseUrl/__baafoo__/api/agents" -Headers $apiKeyHeader -TimeoutSec 10
    $agentList = @()
    if ($agentsResp -is [array]) { $agentList = $agentsResp }
    elseif ($agentsResp.data -is [array]) { $agentList = $agentsResp.data }
    elseif ($agentsResp.agents -is [array]) { $agentList = $agentsResp.agents }

    $petAgent = $agentList | Where-Object { $_.environment -eq "enterprise-petclinic" } | Select-Object -First 1
    $noClassLoadError = $true
    $errorDetail = ""
    if ($petAgent) {
        $pluginStatuses = $petAgent.pluginStatuses
        if ($pluginStatuses) {
            $errorPlugins = $pluginStatuses | Where-Object { $_.status -eq "ERROR" -or $_.state -eq "ERROR" }
            if ($errorPlugins) {
                $noClassLoadError = $false
                $errorDetail = ($errorPlugins | ForEach-Object { $_.name + ": " + $_.error }) -join "; "
            }
        }
        if ($petAgent.errors -or $petAgent.lastError) {
            $errStr = "$($petAgent.errors) $($petAgent.lastError)"
            if ($errStr -match "ClassNotFoundException|NoClassDefFoundError|LinkageError|ClassCastException") {
                $noClassLoadError = $false
                $errorDetail = $errStr
            }
        }
    }
    Write-Result "EG-PET-009: 无类加载冲突" $(if ($noClassLoadError) { "PASS" } else { "FAIL" }) $errorDetail
} catch {
    Write-Result "EG-PET-009: 无类加载冲突" "FAIL" $_.Exception.Message
}

# ========== EG-PET-010: 内存泄漏检查（短期） ==========
try {
    if ($petEnvId) { Switch-EnvMode $petEnvId "passthrough" }

    # Record initial heap usage via actuator (if available)
    $initHeap = $null
    try {
        $initResp = Invoke-RestMethod -Uri "$AppBaseUrl/actuator/metrics/jvm.memory.used" -UseBasicParsing -TimeoutSec 5
        $initHeap = ($initResp.measurements | Where-Object { $_.statistic -eq "VALUE" } | Select-Object -First 1).value
    } catch {
        # PetClinic may not have actuator; use jstat via docker
        $containerName = "baafoo-enterprise-petclinic"
        try {
            $jstatOut = docker exec $containerName jstat -gc 1 2>$null
            if ($jstatOut) {
                $parts = $jstatOut -split '\s+'
                if ($parts.Count -ge 3) { $initHeap = [double]$parts[2] }  # EU (Eden used)
            }
        } catch {}
    }

    # Run ~60 API calls over 60 seconds (shortened from 1 hour for smoke test)
    $callCount = 0
    for ($i = 0; $i -lt 30; $i++) {
        try {
            Invoke-WebRequest -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 5 | Out-Null
            $callCount++
        } catch {}
        Start-Sleep -Milliseconds 500
    }

    $endHeap = $null
    try {
        $endResp = Invoke-RestMethod -Uri "$AppBaseUrl/actuator/metrics/jvm.memory.used" -UseBasicParsing -TimeoutSec 5
        $endHeap = ($endResp.measurements | Where-Object { $_.statistic -eq "VALUE" } | Select-Object -First 1).value
    } catch {
        try {
            $jstatOut2 = docker exec $containerName jstat -gc 1 2>$null
            if ($jstatOut2) {
                $parts2 = $jstatOut2 -split '\s+'
                if ($parts2.Count -ge 3) { $endHeap = [double]$parts2[2] }
            }
        } catch {}
    }

    if ($initHeap -and $endHeap) {
        $heapDelta = [math]::Round(($endHeap - $initHeap) / 1MB, 2)
        # For smoke test: heap delta < 20MB is acceptable (short run)
        $stable = $heapDelta -lt 20
        Write-Result "EG-PET-010: 内存泄漏检查（短期）" $(if ($stable) { "PASS" } else { "FAIL" }) "heap delta: ${heapDelta}MB (calls=$callCount)"
    } else {
        Write-Result "EG-PET-010: 内存泄漏检查（短期）" "SKIP" "无法获取 JVM 内存指标 (actuator/jstat 不可用)"
    }
} catch {
    Write-Result "EG-PET-010: 内存泄漏检查（短期）" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $petEnvId $origMode
}

# ========== EG-PET-011: CPU 开销评估 ==========
try {
    if ($petEnvId) { Switch-EnvMode $petEnvId "passthrough" }

    # Measure response time with Agent in passthrough (minimal overhead)
    $ptTimes = @()
    for ($i = 0; $i -lt 5; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10 | Out-Null
        $sw.Stop()
        $ptTimes += $sw.ElapsedMilliseconds
    }
    $ptAvg = [math]::Round(($ptTimes | Measure-Object -Average).Average, 1)

    # Measure response time with Agent in stub mode
    Switch-EnvMode $petEnvId "stub"
    $stubTimes = @()
    for ($i = 0; $i -lt 5; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -Uri "$AppBaseUrl/vets" -UseBasicParsing -TimeoutSec 10 | Out-Null
        $sw.Stop()
        $stubTimes += $sw.ElapsedMilliseconds
    }
    $stubAvg = [math]::Round(($stubTimes | Measure-Object -Average).Average, 1)

    $overhead = if ($ptAvg -gt 0) { [math]::Round((($stubAvg - $ptAvg) / $ptAvg) * 100, 1) } else { 0 }
    $acceptable = $overhead -le 200  # Stub adds mock processing; 200% threshold is lenient for smoke
    Write-Result "EG-PET-011: CPU 开销评估" $(if ($acceptable) { "PASS" } else { "FAIL" }) "passthrough avg=${ptAvg}ms, stub avg=${stubAvg}ms, overhead=${overhead}%"
} catch {
    Write-Result "EG-PET-011: CPU 开销评估" "FAIL" $_.Exception.Message
} finally {
    Restore-EnvMode $petEnvId $origMode
}

# ========== EG-PET-012: 异步调用拦截验证 ==========
Write-Result "EG-PET-012: 异步调用拦截验证" "SKIP" "PetClinic REST 无已知 @Async 接口"

# ========== EG-PET-013: 定时任务调用拦截验证 ==========
Write-Result "EG-PET-013: 定时任务调用拦截验证" "SKIP" "PetClinic REST 无已知 @Scheduled 任务"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果: $passed 通过, $failed 失败, $skipped 跳过" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

# ==================== JUnit XML report (CI consumption) ====================
Export-JUnitXml (Join-Path $PSScriptRoot "junit-report.xml")

if ($failed -gt 0) {
    exit 1
}
