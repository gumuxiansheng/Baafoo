# Create Mock rule with BOTH host and serviceName fields
# This enables both lookup paths:
#  - ConsulHttpAdvice: lookup("service-provider", 18081) -> hit (host:port)
#  - ConsulDnsGetByNameAdvice: lookupByHost("service-provider") -> hit (host)
#  - ConsulHttpAdvice fallback: lookupService("service-provider") -> hit (serviceName)
$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}

Write-Host "[1/3] Delete old rules"
foreach ($id in @("sca-provider-echo-mock", "sca-provider-echo-mock-svc")) {
    try {
        Invoke-RestMethod -Uri "$base/rules/$id" -Method Delete -Headers $headers | Out-Null
        Write-Host "  Deleted: $id"
    } catch {
        Write-Host "  Not exists: $id"
    }
}

Write-Host ""
Write-Host "[2/3] Create new rule with host + serviceName"
$newRule = @{
    id = "sca-provider-echo-mock-svc"
    name = "Provider Echo API Mock (host + serviceName)"
    protocol = "http"
    host = "service-provider"
    port = 18081
    serviceName = "service-provider"
    conditions = @(@{ type = "path"; operator = "startsWith"; value = "/echo/" })
    responses = @(@{ name = "Mock Echo response"; statusCode = 200; body = "hello Nacos Discovery mock via serviceName"; delayMs = 0 })
    enabled = $true
    priority = 50
    tags = @("enterprise", "sca", "echo", "servicename")
    environments = @("enterprise-sca-consumer")
}
$body = $newRule | ConvertTo-Json -Compress -Depth 10
try {
    Invoke-RestMethod -Uri "$base/rules" -Method Post -Headers $headers -Body $body | Out-Null
    Write-Host "  Created: host=service-provider, serviceName=service-provider, port=18081"
} catch {
    Write-Host "  Failed: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "[3/3] Verify rule"
$rules = Invoke-RestMethod -Uri "$base/rules" -Headers $headers
$svcRule = $rules.data | Where-Object { $_.id -eq "sca-provider-echo-mock-svc" } | Select-Object -First 1
if ($svcRule) {
    Write-Host "  id=$($svcRule.id), host=$($svcRule.host), port=$($svcRule.port), serviceName=$($svcRule.serviceName), enabled=$($svcRule.enabled)"
} else {
    Write-Host "  Rule not found!"
}

Write-Host ""
Write-Host "Waiting 15s for agent poll..."
Start-Sleep -Seconds 15

Write-Host ""
Write-Host "Verify agent polled rule:"
$pollResp = Invoke-RestMethod -Uri "$base/agent/poll?agentId=babc5364102d&environment=enterprise-sca-consumer" -Headers $headers
$polled = $pollResp.data.rules | Where-Object { $_.id -eq "sca-provider-echo-mock-svc" } | Select-Object -First 1
if ($polled) {
    Write-Host "  Polled: host=$($polled.host), port=$($polled.port), serviceName=$($polled.serviceName), enabled=$($polled.enabled)"
} else {
    Write-Host "  Agent has not polled the rule yet"
}
