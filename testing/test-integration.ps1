# =============================================================================
# Baafoo 集成测试一键脚本
# 用法: .\scripts\test-integration.ps1
# =============================================================================

$ErrorActionPreference = "Stop"

Write-Host "=== Baafoo 集成测试 ===" -ForegroundColor Cyan

# 1. 清理旧环境
Write-Host "[1/4] 清理旧环境..." -ForegroundColor Yellow
docker compose -f docker-compose.yml -f docker-compose.test.yml --env-file .env.test down -v --remove-orphans 2>$null

# 2. 构建镜像
Write-Host "[2/4] 构建镜像..." -ForegroundColor Yellow
docker compose -f docker-compose.yml --env-file .env.test build --progress=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "构建失败！" -ForegroundColor Red
    exit 1
}

# 3. 启动测试环境
Write-Host "[3/4] 启动测试环境..." -ForegroundColor Yellow
docker compose -f docker-compose.yml -f docker-compose.test.yml --env-file .env.test up --abort-on-container-exit --exit-code-from test-runner

$exitCode = $LASTEXITCODE

# 4. 清理
Write-Host "[4/4] 清理测试环境..." -ForegroundColor Yellow
docker compose -f docker-compose.yml -f docker-compose.test.yml --env-file .env.test down -v

if ($exitCode -eq 0) {
    Write-Host "=== 集成测试通过 ✓ ===" -ForegroundColor Green
} else {
    Write-Host "=== 集成测试失败 ✗ ===" -ForegroundColor Red
}
exit $exitCode
