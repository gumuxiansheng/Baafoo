# Create serviceName-based Mock rule (delete old host-based rule first)
$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}

Write-Host "[1/3] Delete old host-based rule (sca-provider-echo-mock)"
try {
    Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Method Delete -Headers $headers | Out-Null
    Write-Host "  Deleted"
} catch {
    Write-Host "  Not exists or already deleted: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "[2/3] Create new serviceName-based rule"
$newRule = @{
    id = "sca-provider-echo-mock-svc"
    name = "Provider Echo API Mock (serviceName based)"
    protocol = "http"
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
    Write-Host "  Created: serviceName=service-provider"
} catch {
    Write-Host "  Failed: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "[3/3] Verify rule list"
$rules = Invoke-RestMethod -Uri "$base/rules" -Headers $headers
$consumerRules = $rules.data | Where-Object { $_.environments -contains "enterprise-sca-consumer" }
foreach ($r in $consumerRules) {
    Write-Host "  Rule: id=$($r.id), serviceName=$($r.serviceName), host=$($r.host), enabled=$($r.enabled)"
}

Write-Host ""
Write-Host "Done. Waiting 15s for agent poll..."
Start-Sleep -Seconds 15

Write-Host ""
Write-Host "Verify agent picked up the rule:"
$pollResp = Invoke-RestMethod -Uri "$base/agent/poll?agentId=39cc43748c44&environment=enterprise-sca-consumer" -Headers $headers
$svcRule = $pollResp.data.rules | Where-Object { $_.id -eq "sca-provider-echo-mock-svc" } | Select-Object -First 1
if ($svcRule) {
    Write-Host "  Agent polled rule: id=$($svcRule.id), serviceName=$($svcRule.serviceName), enabled=$($svcRule.enabled)"
} else {
    Write-Host "  Agent has not polled the rule yet"
}
