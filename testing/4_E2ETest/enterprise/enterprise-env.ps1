# Baafoo 企业级应用测试 - 环境管理脚本
# 用于快速启动/停止企业级应用测试环境
#
# 用法:
#   .\enterprise-env.ps1 -Action start -Apps kafka,petclinic
#   .\enterprise-env.ps1 -Action stop -Apps kafka,petclinic
#   .\enterprise-env.ps1 -Action start -Apps all
#   .\enterprise-env.ps1 -Action stop -Apps all

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("start", "stop", "restart", "status")]
    [string]$Action,

    [string[]]$Apps = @("kafka", "petclinic"),

    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$baseComposeFile = Join-Path $scriptDir "common\docker-compose.base.yml"

$allApps = @("kafka", "petclinic", "spring-cloud-alibaba")

if ($Apps -contains "all") {
    $Apps = $allApps
}

function Invoke-DockerCompose {
    param(
        [string]$AppDir,
        [string[]]$Args
    )

    $appComposeFile = Join-Path $AppDir "docker-compose.yml"
    if (-not (Test-Path $appComposeFile)) {
        Write-Host "  [SKIP] 配置文件不存在: $appComposeFile" -ForegroundColor Yellow
        return $false
    }

    Push-Location $AppDir
    try {
        $cmdArgs = @("-f", $baseComposeFile, "-f", $appComposeFile) + $Args
        & docker compose @cmdArgs
        return ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Baafoo 企业级应用测试环境管理" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "操作: $Action"
Write-Host "应用: $($Apps -join ', ')"
Write-Host ""

$successCount = 0
$failCount = 0

foreach ($app in $Apps) {
    $appDir = Join-Path $scriptDir $app
    if (-not (Test-Path $appDir)) {
        Write-Host "[SKIP] $app - 目录不存在" -ForegroundColor Yellow
        continue
    }

    Write-Host "--------------------------------------------" -ForegroundColor DarkCyan
    Write-Host "  $app : $Action" -ForegroundColor DarkCyan
    Write-Host "--------------------------------------------" -ForegroundColor DarkCyan

    try {
        switch ($Action) {
            "start" {
                if ($NoBuild) {
                    $result = Invoke-DockerCompose -AppDir $appDir -Args @("up", "-d")
                } else {
                    $result = Invoke-DockerCompose -AppDir $appDir -Args @("up", "--build", "-d")
                }
            }
            "stop" {
                $result = Invoke-DockerCompose -AppDir $appDir -Args @("down", "-v")
            }
            "restart" {
                $result = Invoke-DockerCompose -AppDir $appDir -Args @("down", "-v")
                if ($result) {
                    if ($NoBuild) {
                        $result = Invoke-DockerCompose -AppDir $appDir -Args @("up", "-d")
                    } else {
                        $result = Invoke-DockerCompose -AppDir $appDir -Args @("up", "--build", "-d")
                    }
                }
            }
            "status" {
                $result = Invoke-DockerCompose -AppDir $appDir -Args @("ps")
            }
        }

        if ($result) {
            Write-Host "  [OK] $app $Action 成功" -ForegroundColor Green
            $successCount++
        } else {
            Write-Host "  [FAIL] $app $Action 失败" -ForegroundColor Red
            $failCount++
        }
    } catch {
        Write-Host "  [ERROR] $app 异常: $($_.Exception.Message)" -ForegroundColor Red
        $failCount++
    }

    Write-Host ""
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  完成: $successCount 成功, $failCount 失败" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

if ($failCount -gt 0) {
    exit 1
}
