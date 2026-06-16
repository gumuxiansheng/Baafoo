$rules = Get-Content "test-rules/all-protocols-rules.json" -Raw | ConvertFrom-Json
$results = @()
foreach ($rule in $rules) {
    $json = $rule | ConvertTo-Json -Depth 10 -Compress
    $resp = curl.exe -s -X POST http://localhost:8084/__baafoo__/api/rules -H "Content-Type: application/json" -H "X-Api-Key: staging-admin-key" -d $json
    $results += "$($rule.id): $resp"
}
$results | ForEach-Object { Write-Host $_ }
