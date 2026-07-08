$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}

# Delete existing rule
Write-Host "Deleting old rule..."
$delResp = Invoke-RestMethod -Uri "$base/rules/sca-provider-echo-mock" -Method Delete -Headers $headers
Write-Host "  Delete: $($delResp.success)"

# Recreate without host constraint - matches by port only
$newRule = @{
    id = "sca-provider-echo-mock"
    name = "Provider Echo API Mock (no host filter)"
    protocol = "http"
    host = ""       # empty: don't filter by host
    port = 18081    # filter by port only
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
Write-Host "Creating new rule without host filter..."
$resp = Invoke-RestMethod -Uri "$base/rules" -Method Post -Headers $headers -Body $body
Write-Host "  Create: success=$($resp.success) code=$($resp.code)"
Write-Host "  Rule host='$($resp.data.host)' port=$($resp.data.port) enabled=$($resp.data.enabled)"

# Wait for agent poll
Write-Host "Waiting 15s for agent poll..."
Start-Sleep -Seconds 15

# Test: should return mock data
Write-Host ""
Write-Host "Testing /echo-feign/test (should return mock data):"
$feignResp = Invoke-RestMethod -Uri "http://localhost:18083/echo-feign/test" -Method Get
Write-Host "  Response: $feignResp"

Write-Host ""
Write-Host "Testing /divide-feign?a=10&b=2 (should return 5 - real calculation):"
$divideResp = Invoke-RestMethod -Uri "http://localhost:18083/divide-feign?a=10&b=2" -Method Get
Write-Host "  Response: $divideResp"

Write-Host ""
Write-Host "Testing /echo-feign/intercepted:"
$test2Resp = Invoke-RestMethod -Uri "http://localhost:18083/echo-feign/intercepted" -Method Get
Write-Host "  Response: $test2Resp"
