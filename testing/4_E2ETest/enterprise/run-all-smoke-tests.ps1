# Baafoo 企业级应用测试 - 统一冒烟测试脚本
# 运行所有企业级应用的冒烟测试
#
# 用法:
#   .\run-all-smoke-tests.ps1              # 运行所有已启动的应用
#   .\run-all-smoke-tests.ps1 -Apps kafka,petclinic  # 指定应用
#   .\run-all-smoke-tests.ps1 -ServerUrl http://localhost:18084

param(
    [string]$ServerUrl = "http://localhost:18084",
    [string]$ApiKey = "enterprise-admin-key",
    [string[]]$Apps = @("kafka", "petclinic")
)

$ErrorActionPreference = "Continue"
$totalPassed = 0
$totalFailed = 0
$appResults = @{}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Baafoo 企业级应用 - 统一冒烟测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Server: $ServerUrl"
Write-Host "Apps:   $($Apps -join ', ')"
Write-Host ""

foreach ($app in $Apps) {
    $appDir = Join-Path $scriptDir $app
    $smokeTestScript = Join-Path $appDir "smoke-test.ps1"

    if (-not (Test-Path $smokeTestScript)) {
        Write-Host "[SKIP] $app - 测试脚本不存在" -ForegroundColor Yellow
        continue
    }

    Write-Host "--------------------------------------------" -ForegroundColor DarkCyan
    Write-Host "  运行 $app 冒烟测试..." -ForegroundColor DarkCyan
    Write-Host "--------------------------------------------" -ForegroundColor DarkCyan

    try {
        & $smokeTestScript -ServerBaseUrl $ServerUrl -ApiKey $ApiKey
        $exitCode = $LASTEXITCODE

        if ($exitCode -eq 0) {
            $appResults[$app] = "PASS"
            $totalPassed++
        } else {
            $appResults[$app] = "FAIL"
            $totalFailed++
        }
    } catch {
        Write-Host "[ERROR] $app 测试执行异常: $($_.Exception.Message)" -ForegroundColor Red
        $appResults[$app] = "ERROR"
        $totalFailed++
    }

    Write-Host ""
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  汇总结果" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

foreach ($app in $appResults.Keys) {
    $status = $appResults[$app]
    $color = switch ($status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        "ERROR" { "Yellow" }
        default { "Gray" }
    }
    Write-Host "  $app : $status" -ForegroundColor $color
}

Write-Host ""
Write-Host "  总计: $totalPassed 通过, $totalFailed 失败" -ForegroundColor $(if ($totalFailed -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

if ($totalFailed -gt 0) {
    exit 1
}
