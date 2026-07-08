$j = Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-rec.json -Raw | ConvertFrom-Json
$recs = $j.data
Write-Host ("Total recordings: " + $recs.Count)
foreach ($r in $recs) {
    Write-Host ("---")
    Write-Host ("  id=" + $r.id)
    Write-Host ("  protocol=" + $r.protocol)
    Write-Host ("  direction=" + $r.direction)
    Write-Host ("  ruleId=" + $r.ruleId)
    Write-Host ("  ruleName=" + $r.ruleName)
    Write-Host ("  agentId=" + $r.agentId)
    Write-Host ("  environmentId=" + $r.environmentId)
}
