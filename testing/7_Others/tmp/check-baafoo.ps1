$rules = (Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-rules.json -Raw | ConvertFrom-Json).data
Write-Host ("Total rules: " + $rules.Count)
$jms = $rules | Where-Object { $_.protocol -eq 'jms' }
Write-Host ("JMS rules: " + $jms.Count)
foreach ($r in $jms) {
    Write-Host ("  - " + $r.id + " envs=" + ($r.environments -join ',') + " enabled=" + $r.enabled)
}

Write-Host ""
Write-Host "=== Agents ==="
$agents = (Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-agents.json -Raw | ConvertFrom-Json).data
foreach ($a in $agents) {
    Write-Host ("agentId=" + $a.agentId + " env=" + $a.environment + " ip=" + $a.agentIp + " hb=" + $a.lastHeartbeat)
}
