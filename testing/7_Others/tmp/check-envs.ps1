Write-Host "=== Environments ==="
$envs = (Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-envs.json -Raw | ConvertFrom-Json).data
foreach ($e in $envs) {
    Write-Host ("  " + $e.name + ": mode=" + $e.mode)
}

Write-Host ""
Write-Host "=== Agents ==="
$agents = (Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-agents.json -Raw | ConvertFrom-Json).data
foreach ($a in $agents) {
    Write-Host ("  agentId=" + $a.agentId + " env=" + $a.environment + " ip=" + $a.agentIp)
}
