$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}

# Get the current rule
$rule = (Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Headers $headers).data

# Show before
Write-Host "Before: host=$($rule.host), port=$($rule.port)"

# Remove host constraint (set to null) so it matches any host on port 18081
$rule.host = $null
$body = $rule | ConvertTo-Json -Compress -Depth 10
$resp = Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Method Put -Headers $headers -Body $body
Write-Host "Updated: success=$($resp.success) host=$($resp.data.host)"

# Verify
Start-Sleep -Seconds 2
$rule2 = (Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Headers $headers).data
Write-Host "After: host=$($rule2.host), port=$($rule2.port), enabled=$($rule2.enabled)"
