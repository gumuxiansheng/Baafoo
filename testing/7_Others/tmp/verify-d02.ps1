$json = Get-Content C:\Users\win11\AppData\Local\Temp\baafoo-rec.json -Raw
$hasProduce = $json -match '"protocol":"jms".*?"direction":"produce"'
$hasConsume = $json -match '"protocol":"jms".*?"direction":"consume"'
Write-Host "Has JMS produce: $hasProduce"
Write-Host "Has JMS consume: $hasConsume"
if ($hasProduce -and $hasConsume) {
    Write-Host "D02: PASS - JMS recording has produce/consume direction"
} else {
    Write-Host "D02: FAIL - JMS recording missing produce or consume direction"
}
