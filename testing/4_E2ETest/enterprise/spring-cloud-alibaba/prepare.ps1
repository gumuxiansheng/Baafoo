# =============================================================================
# Spring Cloud Alibaba 企业级测试 - 环境准备脚本
# 1. 从 spring-cloud-alibaba examples 复制编译好的 JAR
# 2. 从 Baafoo 复制 Agent JAR
# 3. 准备好 build context 供 docker compose build 使用
# =============================================================================

param(
    [string]$BaafooRoot = "C:\Dev\Projects\Baafoo",
    [string]$ScaRoot = "C:\Dev\Projects\spring-cloud-alibaba"
)

$ErrorActionPreference = "Stop"
$targetDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "=== Spring Cloud Alibaba 企业级测试环境准备 ===" -ForegroundColor Cyan
Write-Host "目标目录: $targetDir"
Write-Host ""

# 1. 复制 Provider JAR
$providerJar = Get-ChildItem "$ScaRoot\spring-cloud-alibaba-examples\nacos-example\nacos-discovery-example\nacos-discovery-provider-example\target\nacos-discovery-provider-example-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $providerJar) {
    Write-Host "[ERROR] 未找到 Provider JAR，请先编译 spring-cloud-alibaba-examples" -ForegroundColor Red
    exit 1
}
Copy-Item $providerJar.FullName "$targetDir\provider-app.jar" -Force
Write-Host "[OK] Provider JAR: $($providerJar.Name) -> provider-app.jar" -ForegroundColor Green

# 2. 复制 Consumer JAR
$consumerJar = Get-ChildItem "$ScaRoot\spring-cloud-alibaba-examples\nacos-example\nacos-discovery-example\nacos-discovery-consumer-example\target\nacos-discovery-consumer-example-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $consumerJar) {
    Write-Host "[ERROR] 未找到 Consumer JAR，请先编译 spring-cloud-alibaba-examples" -ForegroundColor Red
    exit 1
}
Copy-Item $consumerJar.FullName "$targetDir\consumer-app.jar" -Force
Write-Host "[OK] Consumer JAR: $($consumerJar.Name) -> consumer-app.jar" -ForegroundColor Green

# 3. 复制 Baafoo Agent JAR
$agentJar = Get-ChildItem "$BaafooRoot\baafoo-agent\target\baafoo-agent-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $agentJar) {
    Write-Host "[ERROR] 未找到 Baafoo Agent JAR，请先构建 Baafoo 项目" -ForegroundColor Red
    exit 1
}
Copy-Item $agentJar.FullName "$targetDir\baafoo-agent.jar" -Force
Write-Host "[OK] Agent JAR: $($agentJar.Name) -> baafoo-agent.jar" -ForegroundColor Green

Write-Host ""
Write-Host "=== 准备完成 ===" -ForegroundColor Cyan
Write-Host "现在可以运行: docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up -d --build"
