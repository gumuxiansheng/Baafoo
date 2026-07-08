$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}

# Delete the rule with empty host
Write-Host "Deleting rule with empty host..."
$del = Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Method Delete -Headers $headers
Write-Host "  Delete: $($del.success)"

# Recreate with host=provider (Docker service DNS name)
$newRule = @{
    id = "sca-provider-echo-mock"
    name = "Provider Echo API Mock (host=provider DNS)"
    protocol = "http"
    host = "provider"
    port = 18081
    conditions = @(
        @{ type = "path"; operator = "startsWith"; value = "/echo/" }
    )
    responses = @(
        @{
            name = "Mock Echo response"
            statusCode = 200
            body = "hello Nacos Discovery mock"
            delayMs = 0
        }
    )
    enabled = $true
    priority = 50
    tags = @("enterprise", "sca", "echo")
    environments = @("enterprise-sca-consumer")
}

$body = $newRule | ConvertTo-Json -Compress -Depth 10
Write-Host "Creating new rule with host=provider..."
$resp = Invoke-RestMethod -Uri "$base/rules" -Method Post -Headers $headers -Body $body
Write-Host "  Create: success=$($resp.success) code=$($resp.code)"
Write-Host "  Rule host='$($resp.data.host)' port=$($resp.data.port) enabled=$($resp.data.enabled)"
