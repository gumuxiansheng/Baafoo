# Spring Cloud Alibaba Enterprise Test Runner - Robust version
# Test Cases: EG-INT-001 / EG-INT-003 / EG-INT-008
$ErrorActionPreference = "Continue"
$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}
$results = [System.Collections.Generic.List[pscustomobject]]::new()
function Add-Result($id, $name, $status, $expected, $actual, $evidence) {
    $results.Add([pscustomobject]@{
        CaseId = $id; Name = $name; Status = $status
        Expected = $expected; Actual = $actual; Evidence = $evidence
    })
    $color = if ($status -eq "PASS") { "Green" } elseif ($status -eq "SKIP") { "Yellow" } else { "Red" }
    Write-Host ("[{0}] {1} - {2}" -f $status, $id, $name) -ForegroundColor $color
}
function Get-Body($url) {
    $resp = Invoke-WebRequest -Uri $url -Method Get -UseBasicParsing
    return $resp.Content
}

# Wait until agent poll reflects the desired enabled state for the mock rule
function Wait-ForAgentRuleState($expectedEnabled, $maxWaitSec = 40) {
    $start = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt $maxWaitSec) {
        Start-Sleep -Seconds 3
        try {
            $pollResp = Invoke-RestMethod -Uri "$base/agent/poll?agentId=39cc43748c44&environment=enterprise-sca-consumer" -Headers $headers
            $rules = $pollResp.data.rules
            $target = $rules | Where-Object { $_.id -eq "sca-provider-echo-mock" } | Select-Object -First 1
            if ($target -and $target.enabled -eq $expectedEnabled) {
                return $true
            }
            Write-Host "  Agent poll: rule enabled=$($target.enabled), waiting..."
        } catch {
            Write-Host "  Agent poll failed: $($_.Exception.Message)"
        }
    }
    return $false
}

function Set-RuleEnabled($enabled) {
    $ruleResp = Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Headers $headers
    $rule = $ruleResp.data
    $rule.enabled = $enabled
    $body = $rule | ConvertTo-Json -Compress -Depth 10
    Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Method Put -Headers $headers -Body $body | Out-Null
}

Write-Host "============================================================"
Write-Host " EG-INT-001: Service Registration Interception"
Write-Host "============================================================"
Write-Host ""
Write-Host "[1.1] Verify both agents registered to Baafoo Server"
try {
    $agentsResp = Invoke-RestMethod -Uri "$base/agents" -Headers $headers
    $agents = $agentsResp.data
    $providerAgent = $agents | Where-Object { $_.environment -eq "enterprise-sca-provider" } | Select-Object -First 1
    $consumerAgent = $agents | Where-Object { $_.environment -eq "enterprise-sca-consumer" } | Select-Object -First 1
    if ($providerAgent -and $consumerAgent) {
        Add-Result "EG-INT-001" "Agents registered to Baafoo Server" "PASS" `
            "Both agents register to Baafoo Server" `
            ("Provider Agent: {0} (IP:{1}), Consumer Agent: {2} (IP:{3})" -f $providerAgent.agentId, $providerAgent.agentIp, $consumerAgent.agentId, $consumerAgent.agentIp) `
            "agents API returns 2 records"
    } else {
        Add-Result "EG-INT-001" "Agents registered to Baafoo Server" "FAIL" `
            "Both agents register to Baafoo Server" `
            "Provider=$($null -ne $providerAgent), Consumer=$($null -ne $consumerAgent)" `
            "agents API missing records"
    }
} catch {
    Add-Result "EG-INT-001" "Agents registered to Baafoo Server" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "[1.2] Verify Provider/Consumer registered to Nacos"
try {
    $servicesBody = Get-Body "http://localhost:18083/services"
    $services = $servicesBody | ConvertFrom-Json
    $hasProvider = $services -contains "service-provider"
    $hasConsumer = $services -contains "service-consumer"
    if ($hasProvider -and $hasConsumer) {
        Add-Result "EG-INT-001" "Services registered to Nacos" "PASS" `
            "service-provider and service-consumer both register to Nacos" `
            "Nacos services: $($services -join ', ')" `
            "consumer /services endpoint returns both service names"
    } else {
        Add-Result "EG-INT-001" "Services registered to Nacos" "FAIL" `
            "service-provider and service-consumer both register to Nacos" `
            "Nacos services: $($services -join ', ')" `
            "consumer /services endpoint missing some services"
    }
} catch {
    Add-Result "EG-INT-001" "Services registered to Nacos" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "[1.3] Verify Agent does not break service discovery"
try {
    $instancesBody = Get-Body "http://localhost:18083/services/service-provider"
    $instances = $instancesBody | ConvertFrom-Json
    $instanceCount = @($instances).Count
    if ($instanceCount -ge 1) {
        $inst = $instances[0]
        Add-Result "EG-INT-001" "Agent does not break discovery" "PASS" `
            "Consumer can find Provider instances via Nacos" `
            "Provider instance: ip=$($inst.ip), port=$($inst.port), healthy=$($inst.healthy)" `
            "discoveryClient.getInstances() returns real Provider instance"
    } else {
        Add-Result "EG-INT-001" "Agent does not break discovery" "FAIL" `
            "Consumer can find Provider instances via Nacos" `
            "No Provider instance" "instances count=0"
    }
} catch {
    Add-Result "EG-INT-001" "Agent does not break discovery" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "============================================================"
Write-Host " EG-INT-003: Feign Call Interception"
Write-Host "============================================================"
Write-Host ""
Write-Host "[3.1] Verify Mock rule is enabled and Feign call is intercepted"
try {
    # Ensure rule is enabled
    Set-RuleEnabled $true
    Start-Sleep -Seconds 12
    $resp = Get-Body "http://localhost:18083/echo-feign/test"
    if ($resp -eq "hello Nacos Discovery mock") {
        Add-Result "EG-INT-003" "Feign call mocked" "PASS" `
            "Mock enabled: returns 'hello Nacos Discovery mock'" `
            "Response: $resp" "agent intercepts consumer->provider outbound HTTP"
    } else {
        Add-Result "EG-INT-003" "Feign call mocked" "FAIL" `
            "returns Mock data 'hello Nacos Discovery mock'" `
            "Response: $resp" "response does not match expected Mock"
    }
} catch {
    Add-Result "EG-INT-003" "Feign call mocked" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "[3.2] Verify Mock only applies to /echo/* path"
try {
    $resp = Get-Body "http://localhost:18083/divide-feign?a=10&b=2"
    if ($resp -eq "5") {
        Add-Result "EG-INT-003" "Mock path precise match" "PASS" `
            "Mock only applies to /echo/*, /divide unaffected" `
            "Response: $resp (10/2=5)" "/divide-feign returns real calculation"
    } else {
        Add-Result "EG-INT-003" "Mock path precise match" "FAIL" `
            "/divide-feign?a=10&b=2 returns 5" `
            "Response: $resp" "/divide also incorrectly mocked"
    }
} catch {
    Add-Result "EG-INT-003" "Mock path precise match" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "[3.3] Verify Provider direct call (no Mock) returns real data"
try {
    $resp = Get-Body "http://localhost:18081/echo/direct"
    if ($resp -like "*hello Nacos Discovery direct*") {
        Add-Result "EG-INT-003" "Provider direct call" "PASS" `
            "Provider direct (no Agent intercept inbound) returns real" `
            "Response: $resp" "Provider Agent does not intercept inbound HTTP"
    } else {
        Add-Result "EG-INT-003" "Provider direct call" "FAIL" `
            "Provider direct returns real" `
            "Response: $resp" "unexpected response"
    }
} catch {
    Add-Result "EG-INT-003" "Provider direct call" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "============================================================"
Write-Host " EG-INT-008: Multi-service Agent Coexistence"
Write-Host "============================================================"
Write-Host ""
Write-Host "[8.1] Verify Provider Agent and Consumer Agent do not interfere"
try {
    $providerResp = Get-Body "http://localhost:18081/echo/direct"
    $consumerResp = Get-Body "http://localhost:18083/echo-feign/direct"
    if ($providerResp -like "*hello Nacos Discovery direct*" -and $consumerResp -eq "hello Nacos Discovery mock") {
        Add-Result "EG-INT-008" "Agents work independently" "PASS" `
            "Provider direct returns real, Consumer Feign mocked by its Agent" `
            "Provider=$providerResp; Consumer Feign=$consumerResp" `
            "Provider Agent no inbound intercept, Consumer Agent outbound intercept"
    } else {
        Add-Result "EG-INT-008" "Agents work independently" "FAIL" `
            "Provider direct real, Consumer Feign mocked" `
            "Provider=$providerResp; Consumer Feign=$consumerResp" "agent behavior not as expected"
    }
} catch {
    Add-Result "EG-INT-008" "Agents work independently" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "[8.2] Verify Mock rule environment isolation"
try {
    $allRulesResp = Invoke-RestMethod -Uri "$base/rules" -Headers $headers
    $allRules = $allRulesResp.data
    $consumerRules = $allRules | Where-Object { $_.environments -contains "enterprise-sca-consumer" }
    $providerRules = $allRules | Where-Object { $_.environments -contains "enterprise-sca-provider" }
    Write-Host "  Consumer env rules: $($consumerRules.Count)"
    Write-Host "  Provider env rules: $($providerRules.Count)"
    if ($consumerRules.Count -ge 1 -and $providerRules.Count -eq 0) {
        Add-Result "EG-INT-008" "Environment isolation rules" "PASS" `
            "Mock rules only bound to Consumer env, Provider env has none" `
            "Consumer rules=$($consumerRules.Count), Provider rules=$($providerRules.Count)" `
            "environment isolation works as expected"
    } else {
        Add-Result "EG-INT-008" "Environment isolation rules" "FAIL" `
            "Mock rules only bound to Consumer env" `
            "Consumer rules=$($consumerRules.Count), Provider rules=$($providerRules.Count)" "env binding not as expected"
    }
} catch {
    Add-Result "EG-INT-008" "Environment isolation rules" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "[8.3] Verify both agents heartbeats alive"
try {
    $agentsResp = Invoke-RestMethod -Uri "$base/agents" -Headers $headers
    $agents = $agentsResp.data
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $aliveCount = 0
    foreach ($a in $agents) {
        $diff = $now - $a.lastHeartbeat
        if ($diff -lt 30000) { $aliveCount++ }
    }
    if ($aliveCount -eq 2) {
        Add-Result "EG-INT-008" "Agent heartbeat alive" "PASS" `
            "Both agents heartbeat interval < 30s" `
            "alive agents = $aliveCount / 2" "heartbeat mechanism normal"
    } else {
        Add-Result "EG-INT-008" "Agent heartbeat alive" "FAIL" `
            "Both agents heartbeat interval < 30s" `
            "alive agents = $aliveCount / 2" "heartbeat abnormal"
    }
} catch {
    Add-Result "EG-INT-008" "Agent heartbeat alive" "ERROR" "API call success" $_.Exception.Message "exception"
}

Write-Host ""
Write-Host "============================================================"
Write-Host " Test Result Summary"
Write-Host "============================================================"
$results | Format-Table CaseId, Name, Status -AutoSize
$pass = ($results | Where-Object { $_.Status -eq "PASS" }).Count
$fail = ($results | Where-Object { $_.Status -eq "FAIL" }).Count
$err = ($results | Where-Object { $_.Status -eq "ERROR" }).Count
Write-Host ""
Write-Host ("Total: {0} PASS / {1} FAIL / {2} ERROR / {3} all" -f $pass, $fail, $err, $results.Count)
$results | Export-Csv -Path "c:\Dev\Projects\Baafoo\testing\7_Others\tmp\sca-test-results.csv" -NoTypeInformation -Encoding UTF8
Write-Host "Results saved to: testing\7_Others\tmp\sca-test-results.csv"
