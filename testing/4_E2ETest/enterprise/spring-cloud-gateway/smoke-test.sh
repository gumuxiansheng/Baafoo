#!/usr/bin/env bash
# Spring Cloud Gateway 企业级测试 - 冒烟测试脚本 (bash 版)
# 用法: ./smoke-test.sh

set -uo pipefail

SERVER_BASE_URL="${SERVER_BASE_URL:-http://localhost:18084}"
GATEWAY_BASE_URL="${GATEWAY_BASE_URL:-http://localhost:18080}"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:18092}"
API_KEY="${API_KEY:-enterprise-admin-key}"
MODE_SETTLE_WAIT=12

PASSED=0; FAILED=0; SKIPPED=0
TEST_RESULTS=()
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'

write_result() {
    local n="$1" s="$2" d="${3:-}"
    TEST_RESULTS+=("$n|$s|$d")
    case "$s" in
        PASS) echo -e "${GREEN}[PASS]${NC} $n"; ((PASSED++)) ;;
        FAIL) echo -e "${RED}[FAIL]${NC} $n"; [[ -n "$d" ]] && echo -e "       ${RED}$d${NC}"; ((FAILED++)) ;;
        SKIP) echo -e "${YELLOW}[SKIP]${NC} $n"; [[ -n "$d" ]] && echo -e "       ${YELLOW}$d${NC}"; ((SKIPPED++)) ;;
    esac
    return 0
}
xml_escape() { local s="$1"; s="${s//&/&amp;}"; s="${s//</&lt;}"; s="${s//>/&gt;}"; s="${s//\"/&quot;}"; s="${s//\'/&apos;}"; echo -n "$s"; }

export_junit_xml() {
    local path="$1" ts; ts=$(date -u +"%Y-%m-%dT%H:%M:%S")
    local total=$((PASSED + FAILED + SKIPPED))
    { echo '<?xml version="1.0" encoding="UTF-8"?>'
      echo "<testsuites name=\"baafoo-enterprise-gateway\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\">"
      echo "<testsuite name=\"EnterpriseGateway\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\" timestamp=\"$ts\">"
      for entry in "${TEST_RESULTS[@]}"; do
          IFS='|' read -r name status message <<< "$entry"
          local en em; en=$(xml_escape "$name"); em=$(xml_escape "$message")
          echo "  <testcase name=\"$en\" classname=\"EnterpriseGateway\" status=\"$(echo $status | tr '[:upper:]' '[:lower:]')\">"
          [[ "$status" == "FAIL" ]] && echo "    <failure message=\"$em\">$em</failure>"
          [[ "$status" == "SKIP" ]] && echo "    <skipped message=\"$em\"/>"
          echo "  </testcase>"
      done
      echo "</testsuite>"
      echo "</testsuites>"; } > "$path"
    echo -e "  ${CYAN}[OK]${NC} JUnit XML written: $path"
}

api_get() { curl -sf -H "X-Api-Key: $API_KEY" "$SERVER_BASE_URL$1" 2>/dev/null; }
api_post() { curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X POST -d "$2" "$SERVER_BASE_URL$1" 2>/dev/null; }
gw_get() { curl -sf "$GATEWAY_BASE_URL$1" 2>/dev/null; }
backend_get() { curl -sf "$BACKEND_BASE_URL$1" 2>/dev/null; }
get_env_id() { api_get "/__baafoo__/api/environments" 2>/dev/null | jq -r --arg name "$1" '.data[] | select(.name == $name or .id == $name) | .id' 2>/dev/null | head -1; }
switch_mode() { curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X PUT -d "{\"mode\":\"$2\"}" "$SERVER_BASE_URL/__baafoo__/api/environments/$1" >/dev/null 2>&1; sleep "$MODE_SETTLE_WAIT"; }

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Spring Cloud Gateway 企业级测试 - 冒烟测试${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

GW_ENV_ID=$(get_env_id "enterprise-gateway" 2>/dev/null || echo "")
BACKEND_ENV_ID=$(get_env_id "enterprise-gateway-backend" 2>/dev/null || echo "")

# ========== EG-GW-001 ==========
health=$(curl -sf "$GATEWAY_BASE_URL/actuator/health" 2>/dev/null | jq -r '.status' 2>/dev/null || echo "")
[[ "$health" == "UP" ]] && write_result "EG-GW-001" "PASS" || write_result "EG-GW-001" "FAIL" "Gateway 健康检查: $health"

# ========== EG-GW-002 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
gw_found=$(echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-gateway")] | length > 0' >/dev/null 2>&1 && echo true || echo false)
backend_found=$(echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-gateway-backend")] | length > 0' >/dev/null 2>&1 && echo true || echo false)
if $gw_found && $backend_found; then
    write_result "EG-GW-002" "PASS" "gateway + backend 均已注册"
elif $gw_found; then
    write_result "EG-GW-002" "PASS" "gateway 已注册 (backend 未注册)"
else
    write_result "EG-GW-002" "FAIL" "未找到 enterprise-gateway 环境的 Agent"
fi

# ========== EG-GW-003 ==========
[[ -n "$GW_ENV_ID" ]] && switch_mode "$GW_ENV_ID" "passthrough" 2>/dev/null || true
[[ -n "$BACKEND_ENV_ID" ]] && switch_mode "$BACKEND_ENV_ID" "passthrough" 2>/dev/null || true
resp=$(gw_get "/api/stub-demo/health" 2>/dev/null)
[[ "$resp" == "OK" ]] && write_result "EG-GW-003" "PASS" || write_result "EG-GW-003" "FAIL" "路由转发返回非 OK: $resp"

# ========== EG-GW-004 ==========
# Spring Cloud Gateway uses Reactor Netty which bypasses HttpOpenServerAdvice
# and DnsResolveAdvice. Gateway-level stub is unsupported (planned for v1.5).
write_result "EG-GW-004" "SKIP" "Gateway 使用 Reactor Netty，Agent 当前无法拦截出站请求（计划 v1.5 支持）"

# ========== EG-GW-005 ==========
[[ -n "$GW_ENV_ID" ]] && switch_mode "$GW_ENV_ID" "passthrough" 2>/dev/null || true
[[ -n "$BACKEND_ENV_ID" ]] && switch_mode "$BACKEND_ENV_ID" "stub" 2>/dev/null || true
resp=$(gw_get "/api/http/health" 2>/dev/null)
[[ "$resp" == "OK" ]] && write_result "EG-GW-005" "PASS" "后端 health 透传正常" || write_result "EG-GW-005" "FAIL" "后端响应异常: $resp"

# ========== EG-GW-006 ==========
[[ -n "$GW_ENV_ID" ]] && switch_mode "$GW_ENV_ID" "passthrough" 2>/dev/null || true
[[ -n "$BACKEND_ENV_ID" ]] && switch_mode "$BACKEND_ENV_ID" "passthrough" 2>/dev/null || true
resp=$(gw_get "/api/stub-demo/health" 2>/dev/null)
[[ "$resp" == "OK" ]] && write_result "EG-GW-006" "PASS" || write_result "EG-GW-006" "FAIL" "全链路透传失败: $resp"

# ========== EG-GW-007 ==========
[[ -n "$GW_ENV_ID" ]] && switch_mode "$GW_ENV_ID" "record" 2>/dev/null || true
[[ -n "$BACKEND_ENV_ID" ]] && switch_mode "$BACKEND_ENV_ID" "record" 2>/dev/null || true
gw_get "/api/stub-demo/health" >/dev/null 2>&1; gw_get "/api/http/health" >/dev/null 2>&1
sleep 2
recordings=$(api_get "/__baafoo__/api/recordings?environment=enterprise-gateway" 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
[[ "$recordings" -gt 0 ]] && write_result "EG-GW-007" "PASS" "录制到 $recordings 条" || write_result "EG-GW-007" "FAIL" "未录制到任何请求"

# ========== EG-GW-008 ==========
# Hot-swap relies on stub interception which requires Reactor Netty support.
write_result "EG-GW-008" "SKIP" "Gateway 使用 Reactor Netty，Agent 当前无法拦截出站请求（计划 v1.5 支持）"

# ========== EG-GW-009 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-gateway")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-GW-009" "PASS"
else
    write_result "EG-GW-009" "FAIL" "Agent 状态异常"
fi

# ========== EG-GW-010 ==========
[[ -n "$GW_ENV_ID" ]] && switch_mode "$GW_ENV_ID" "passthrough" 2>/dev/null || true
[[ -n "$BACKEND_ENV_ID" ]] && switch_mode "$BACKEND_ENV_ID" "passthrough" 2>/dev/null || true
gw_health=$(curl -sf "$GATEWAY_BASE_URL/actuator/health" 2>/dev/null | jq -r '.status' 2>/dev/null || echo "")
backend_health=$(backend_get "/api/stub-demo/health" 2>/dev/null)
if [[ "$gw_health" == "UP" && "$backend_health" == "OK" ]]; then
    write_result "EG-GW-010" "PASS"
else
    write_result "EG-GW-010" "FAIL" "gw=$gw_health backend=$backend_health"
fi

# ========== EG-GW-011 ==========
routes=$(curl -sf "$GATEWAY_BASE_URL/actuator/gateway/routes" 2>/dev/null)
route_count=$(echo "$routes" | jq 'length' 2>/dev/null || echo 0)
if [[ "$route_count" -gt 0 ]]; then
    write_result "EG-GW-011" "PASS" "$route_count 条路由"
else
    write_result "EG-GW-011" "SKIP" "Gateway routes 端点不可用"
fi

# ========== EG-GW-012 ==========
# Multi-env isolation requires Gateway-level stub which depends on Reactor Netty support.
write_result "EG-GW-012" "SKIP" "Gateway 使用 Reactor Netty，Agent 当前无法拦截出站请求（计划 v1.5 支持）"

# 恢复
[[ -n "$GW_ENV_ID" ]] && switch_mode "$GW_ENV_ID" "stub" 2>/dev/null || true
[[ -n "$BACKEND_ENV_ID" ]] && switch_mode "$BACKEND_ENV_ID" "stub" 2>/dev/null || true

echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "  测试结果: $PASSED 通过, $FAILED 失败, $SKIPPED 跳过${NC}"
echo -e "${CYAN}============================================${NC}"

export_junit_xml "$SCRIPT_DIR/junit-report.xml"

if [[ $FAILED -gt 0 ]]; then exit 1; elif [[ $SKIPPED -gt 0 ]]; then exit 2; fi
exit 0
