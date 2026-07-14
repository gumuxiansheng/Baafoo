# Baafoo 企业级应用测试 - 统一冒烟测试脚本
# 运行所有企业级应用的冒烟测试，并聚合 JUnit XML 报告
#
# 用法:
#   .\run-all-smoke-tests.ps1              # 运行所有已启动的应用
#   .\run-all-smoke-tests.ps1 -Apps kafka,petclinic  # 指定应用
#   .\run-all-smoke-tests.ps1 -ServerUrl http://localhost:18084

param(
    [string]$ServerUrl = "http://localhost:18084",
    [string]$ApiKey = "enterprise-admin-key",
    [string[]]$Apps = @("kafka", "petclinic", "spring-cloud-alibaba", "nacos", "spring-cloud-gateway")
)

$ErrorActionPreference = "Continue"
$totalPassed = 0
$totalFailed = 0
$appResults = @{}
$allTestCases = @()   # Aggregated JUnit test cases

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

    # Collect per-app JUnit XML if it exists
    $appJUnit = Join-Path $appDir "junit-report.xml"
    if (Test-Path $appJUnit) {
        try {
            [xml]$xml = Get-Content $appJUnit -Raw -Encoding UTF8
            $suiteName = "Enterprise-$app"
            $className = "Enterprise$app"
            foreach ($tc in $xml.testsuites.testsuite.testcase) {
                $allTestCases += [PSCustomObject]@{
                    Name = "$($tc.name)"
                    ClassName = $className
                    Status = $tc.status
                    Message = if ($tc.failure) { $tc.failure.message } elseif ($tc.skipped) { $tc.skipped.message } else { "" }
                }
            }
        } catch {
            Write-Host "  [WARN] 无法解析 $app JUnit XML: $_" -ForegroundColor Yellow
        }
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

# ==================== Aggregate JUnit XML report ====================
$aggTotal = $allTestCases.Count
$aggFail = ($allTestCases | Where-Object { $_.Status -eq "fail" }).Count
$aggSkip = ($allTestCases | Where-Object { $_.Status -eq "skip" }).Count

$ts = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
$sb = New-Object System.Text.StringBuilder
$null = $sb.Append('<?xml version="1.0" encoding="UTF-8"?>')
$null = $sb.AppendFormat('<testsuites name="baafoo-enterprise-all" tests="{0}" failures="{1}" skipped="{2}" errors="0">', $aggTotal, $aggFail, $aggSkip)

# Group by ClassName to create per-app suites
$grouped = $allTestCases | Group-Object -Property ClassName
foreach ($grp in $grouped) {
    $cn = $grp.Name
    $grpTotal = $grp.Count
    $grpFail = ($grp.Group | Where-Object { $_.Status -eq "fail" }).Count
    $grpSkip = ($grp.Group | Where-Object { $_.Status -eq "skip" }).Count
    $null = $sb.AppendFormat('<testsuite name="{0}" tests="{1}" failures="{2}" skipped="{3}" errors="0" timestamp="{4}">', $cn, $grpTotal, $grpFail, $grpSkip, $ts)
    foreach ($tc in $grp.Group) {
        $nameEsc = [Security.SecurityElement]::Escape($tc.Name)
        $msgEsc = [Security.SecurityElement]::Escape($tc.Message)
        $null = $sb.AppendFormat('<testcase name="{0}" classname="{1}" status="{2}">', $nameEsc, $cn, $tc.Status)
        if ($tc.Status -eq "fail") {
            $null = $sb.AppendFormat('<failure message="{0}">{0}</failure>', $msgEsc)
        } elseif ($tc.Status -eq "skip") {
            $null = $sb.AppendFormat('<skipped message="{0}"/>', $msgEsc)
        }
        $null = $sb.Append('</testcase>')
    }
    $null = $sb.Append('</testsuite>')
}
$null = $sb.Append('</testsuites>')

$aggPath = Join-Path $scriptDir "junit-report.xml"
[System.IO.File]::WriteAllText($aggPath, $sb.ToString(), [System.Text.Encoding]::UTF8)
Write-Host "  [OK] Aggregated JUnit XML written: $aggPath ($aggTotal tests, $aggFail failures, $aggSkip skipped)" -ForegroundColor Gray

if ($totalFailed -gt 0) {
    exit 1
}
