#!/usr/bin/env bash
# Baafoo 企业级应用测试 - 统一冒烟测试脚本 (bash 版)
# 运行所有企业级应用的冒烟测试，并聚合 JUnit XML 报告
#
# 用法:
#   ./run-all-smoke-tests.sh                          # 运行所有已启动的应用
#   ./run-all-smoke-tests.sh kafka,petclinic          # 指定应用
#   ./run-all-smoke-tests.sh --server-url http://localhost:18084

set -euo pipefail

# 默认参数
SERVER_URL="http://localhost:18084"
API_KEY="enterprise-admin-key"
APPS=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 解析参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        --server-url)
            SERVER_URL="$2"
            shift 2
            ;;
        --api-key)
            API_KEY="$2"
            shift 2
            ;;
        --apps)
            APPS="$2"
            shift 2
            ;;
        *)
            # First positional arg = apps list
            if [[ -z "$APPS" && "$1" != --* ]]; then
                APPS="$1"
                shift
            else
                shift
            fi
            ;;
    esac
done

# 默认应用列表
if [[ -z "$APPS" ]]; then
    APPS="kafka,petclinic,spring-cloud-alibaba,nacos,spring-cloud-gateway"
fi

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Baafoo 企业级应用 - 统一冒烟测试${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""
echo "Server: $SERVER_URL"
echo "Apps:   $APPS"
echo ""

# 聚合 JUnit XML
AGG_FILE="$SCRIPT_DIR/junit-report.xml"
AGG_TOTAL=0
AGG_FAIL=0
AGG_SKIP=0
ANY_FAILURE=0

# 初始化聚合 XML
echo '<?xml version="1.0" encoding="UTF-8"?>' > "$AGG_FILE"
echo '<testsuites name="baafoo-enterprise-all">' >> "$AGG_FILE"

IFS=',' read -ra APP_ARRAY <<< "$APPS"
for app in "${APP_ARRAY[@]}"; do
    app=$(echo "$app" | xargs)  # trim whitespace
    app_dir="$SCRIPT_DIR/$app"
    smoke_script="$app_dir/smoke-test.sh"

    if [[ ! -f "$smoke_script" ]]; then
        echo -e "${YELLOW}[SKIP]${NC} $app - 测试脚本不存在"
        continue
    fi

    echo -e "${CYAN}--------------------------------------------${NC}"
    echo -e "${CYAN}  运行 $app 冒烟测试...${NC}"
    echo -e "${CYAN}--------------------------------------------${NC}"

    set +e
    bash "$smoke_script"
    exit_code=$?
    set -e

    if [[ $exit_code -eq 0 ]]; then
        echo -e "  ${GREEN}[OK]${NC} $app 通过"
    elif [[ $exit_code -eq 2 ]]; then
        echo -e "  ${YELLOW}[OK]${NC} $app 通过（有跳过）"
    else
        echo -e "  ${RED}[FAIL]${NC} $app 有失败"
        ANY_FAILURE=1
    fi

    # 收集 JUnit XML
    app_junit="$app_dir/junit-report.xml"
    if [[ -f "$app_junit" ]]; then
        sed -n '/<testsuite/,/<\/testsuite>/p' "$app_junit" >> "$AGG_FILE"
        # 统计
        t=$(grep -o 'tests="[0-9]*"' "$app_junit" | head -1 | grep -o '[0-9]*' || echo 0)
        f=$(grep -o 'failures="[0-9]*"' "$app_junit" | head -1 | grep -o '[0-9]*' || echo 0)
        s=$(grep -o 'skipped="[0-9]*"' "$app_junit" | head -1 | grep -o '[0-9]*' || echo 0)
        AGG_TOTAL=$((AGG_TOTAL + t))
        AGG_FAIL=$((AGG_FAIL + f))
        AGG_SKIP=$((AGG_SKIP + s))
    fi

    echo ""
done

echo '</testsuites>' >> "$AGG_FILE"

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  汇总结果${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""
echo "  总计: $AGG_TOTAL 用例, $AGG_FAIL 失败, $AGG_SKIP 跳过"
echo ""
echo -e "  聚合报告: $AGG_FILE"
echo -e "${CYAN}============================================${NC}"

if [[ $ANY_FAILURE -eq 1 ]]; then
    exit 1
fi
exit 0
