$base = "http://localhost:18084/__baafoo__/api"
$headers = @{
    "Content-Type" = "application/json"
    "X-Api-Key" = "enterprise-admin-key"
}

$envs = @(
    @{ id = "enterprise-sca-provider"; name = "enterprise-sca-provider"; mode = "STUB"; description = "SCA Provider Environment" }
    @{ id = "enterprise-sca-consumer"; name = "enterprise-sca-consumer"; mode = "STUB"; description = "SCA Consumer Environment" }
)

foreach ($env in $envs) {
    $body = $env | ConvertTo-Json -Compress
    Write-Host "Creating environment: $($env.id)"
    $resp = Invoke-RestMethod -Uri "$base/environments" -Method Post -Headers $headers -Body $body
    Write-Host "  -> success=$($resp.success) code=$($resp.code) message=$($resp.message)"
}

Write-Host ""
Write-Host "Listing environments:"
$envList = Invoke-RestMethod -Uri "$base/environments" -Method Get -Headers $headers
$envList | ConvertTo-Json -Depth 5
