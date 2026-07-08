$j = Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-poll.json -Raw | ConvertFrom-Json
$d = $j.data
Write-Host ("mode=" + $d.mode)
Write-Host ("rules count=" + $d.rules.Count)
foreach ($r in $d.rules) {
    Write-Host ("  - " + $r.id + " protocol=" + $r.protocol + " envs=" + ($r.environments -join ','))
}
