#!/usr/bin/env bash
# Nacos 企业级测试 - 冒烟测试脚本 (bash 版)
# 用法: ./smoke-test.sh

set -uo pipefail

SERVER_BASE_URL="${SERVER_BASE_URL:-http://localhost:18084}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:18091}"
NACOS_BASE_URL="${NACOS_BASE_URL:-http://localhost:18848}"
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
      echo "<testsuites name=\"baafoo-enterprise-nacos\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\">"
      echo "<testsuite name=\"EnterpriseNacos\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\" timestamp=\"$ts\">"
      for entry in "${TEST_RESULTS[@]}"; do
          IFS='|' read -r name status message <<< "$entry"
          local en em; en=$(xml_escape "$name"); em=$(xml_escape "$message")
          echo "  <testcase name=\"$en\" classname=\"EnterpriseNacos\" status=\"$(echo $status | tr '[:upper:]' '[:lower:]')\">"
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
app_get() { curl -sf "$APP_BASE_URL$1" 2>/dev/null; }
get_env_id() { api_get "/__baafoo__/api/environments" 2>/dev/null | jq -r --arg name "$1" '.data[] | select(.name == $name or .id == $name) | .id' 2>/dev/null | head -1; }
switch_env_mode() { curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X PUT -d "{\"mode\":\"$2\"}" "$SERVER_BASE_URL/__baafoo__/api/environments/$1" >/dev/null 2>&1; sleep "$MODE_SETTLE_WAIT"; }

test_nacos_api() {
    local nacos_path="$1" method="${2:-GET}"
    local encoded_url; encoded_url=$(python3 -c "import urllib.parse; print(urllib.parse.quote('http://nacos:8848$nacos_path', safe=''))")
    if [[ "$method" == "GET" ]]; then
        app_get "/api/http/get?url=$encoded_url"
    else
        curl -sf "$APP_BASE_URL/api/http/post?url=$encoded_url" -X POST 2>/dev/null
    fi
}

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Nacos 企业级测试 - 冒烟测试${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

# ========== EG-NACOS-001 ==========
resp=$(app_get "/api/stub-demo/health" 2>/dev/null)
[[ "$resp" == "OK" ]] && write_result "EG-NACOS-001" "PASS" || write_result "EG-NACOS-001" "FAIL" "健康检查返回: ${resp:-空}"

# ========== EG-NACOS-002 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-nacos")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-NACOS-002" "PASS"
else
    write_result "EG-NACOS-002" "FAIL" "未找到 enterprise-nacos 环境的 Agent"
fi

# ========== EG-NACOS-003 ==========
switch_env_mode "$(get_env_id enterprise-nacos 2>/dev/null)" "stub" 2>/dev/null || true
resp=$(test_nacos_api "/nacos/v1/ns/instance?serviceName=test-service&ip=10.0.0.1&port=8080" "POST")
if echo "$resp" | jq -e '.statusCode == 200 and .stubbed == true' >/dev/null 2>&1; then
    write_result "EG-NACOS-003" "PASS"
else
    write_result "EG-NACOS-003" "FAIL" "statusCode=$(echo "$resp" | jq -r '.statusCode' 2>/dev/null || echo N/A) stubbed=$(echo "$resp" | jq -r '.stubbed' 2>/dev/null || echo N/A)"
fi

# ========== EG-NACOS-004 ==========
resp=$(test_nacos_api "/nacos/v1/ns/instance/list?serviceName=test-service")
body=$(echo "$resp" | jq -r '.body // ""' 2>/dev/null)
if echo "$body" | grep -q '"mocked"[[:space:]]*:[[:space:]]*true' 2>/dev/null && echo "$body" | grep -q '"name"[[:space:]]*:[[:space:]]*"mock-service"' 2>/dev/null; then
    write_result "EG-NACOS-004" "PASS"
else
    write_result "EG-NACOS-004" "FAIL" "返回内容未包含 Mock 标记: $body"
fi

# ========== EG-NACOS-005 ==========
resp=$(test_nacos_api "/nacos/v1/cs/configs?dataId=test-config&group=DEFAULT_GROUP")
body=$(echo "$resp" | jq -r '.body // ""' 2>/dev/null)
if echo "$body" | grep -q "baafoo-nacos" 2>/dev/null && echo "$body" | grep -q "mock.config=true" 2>/dev/null; then
    write_result "EG-NACOS-005" "PASS"
else
    write_result "EG-NACOS-005" "FAIL" "返回内容未包含 Mock 配置: $body"
fi

# ========== EG-NACOS-006 ==========
NACOS_ENV_ID=$(get_env_id "enterprise-nacos" 2>/dev/null || echo "")
if [[ -n "$NACOS_ENV_ID" ]]; then
    switch_env_mode "$NACOS_ENV_ID" "passthrough" 2>/dev/null || true
    resp=$(test_nacos_api "/nacos/v1/ns/operator/metrics")
    if echo "$resp" | jq -e '.statusCode == 200 and .stubbed != true' >/dev/null 2>&1; then
        write_result "EG-NACOS-006" "PASS"
    else
        write_result "EG-NACOS-006" "FAIL" "透传未到达真实 Nacos"
    fi
else
    write_result "EG-NACOS-006" "SKIP" "环境未找到"
fi

# ========== EG-NACOS-007 ==========
if [[ -n "$NACOS_ENV_ID" ]]; then
    switch_env_mode "$NACOS_ENV_ID" "record" 2>/dev/null || true
    test_nacos_api "/nacos/v1/ns/operator/metrics" >/dev/null 2>&1
    test_nacos_api "/nacos/v1/ns/instance/list?serviceName=default" >/dev/null 2>&1
    sleep 2
    recordings=$(api_get "/__baafoo__/api/recordings?environment=enterprise-nacos" 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    if [[ "$recordings" -gt 0 ]]; then
        write_result "EG-NACOS-007" "PASS"
    else
        write_result "EG-NACOS-007" "FAIL" "未录制到任何请求"
    fi
else
    write_result "EG-NACOS-007" "SKIP" "环境未找到"
fi

# ========== EG-NACOS-008 ==========
if [[ -n "$NACOS_ENV_ID" ]]; then
    switch_env_mode "$NACOS_ENV_ID" "stub" 2>/dev/null || true
    resp_stub=$(test_nacos_api "/nacos/v1/ns/instance/list?serviceName=test-service")
    stubbed=$(echo "$resp_stub" | jq -r '.stubbed // false' 2>/dev/null)

    switch_env_mode "$NACOS_ENV_ID" "passthrough" 2>/dev/null || true
    resp_pt=$(test_nacos_api "/nacos/v1/ns/instance/list?serviceName=test-service")
    passthrough=$(echo "$resp_pt" | jq -r '.stubbed // false' 2>/dev/null | grep -q true && echo false || echo true)

    switch_env_mode "$NACOS_ENV_ID" "stub" 2>/dev/null || true
    resp_stub2=$(test_nacos_api "/nacos/v1/ns/instance/list?serviceName=test-service")
    stubbed_again=$(echo "$resp_stub2" | jq -r '.stubbed // false' 2>/dev/null)

    if [[ "$stubbed" == "true" && "$passthrough" == "true" && "$stubbed_again" == "true" ]]; then
        write_result "EG-NACOS-008" "PASS"
    else
        write_result "EG-NACOS-008" "FAIL" "stub=$stubbed pt=$passthrough stub2=$stubbed_again"
    fi
else
    write_result "EG-NACOS-008" "SKIP" "环境未找到"
fi

# ========== EG-NACOS-009 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-nacos")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-NACOS-009" "PASS"
else
    write_result "EG-NACOS-009" "FAIL" "Agent 状态异常"
fi

# ========== EG-NACOS-010 ==========
switch_env_mode "$NACOS_ENV_ID" "stub" 2>/dev/null || true
health=$(app_get "/api/stub-demo/health" 2>/dev/null)
http_health=$(app_get "/api/http/health" 2>/dev/null)
if [[ "$health" == "OK" && "$http_health" == "OK" ]]; then
    write_result "EG-NACOS-010" "PASS"
else
    write_result "EG-NACOS-010" "FAIL" "health=$health httpHealth=$http_health"
fi

# ========== EG-NACOS-011 ==========
nacos_http_code=$(curl -s -o /dev/null -w '%{http_code}' "$NACOS_BASE_URL/nacos/v1/console/health/readiness" 2>/dev/null)
if [[ "$nacos_http_code" == "200" ]]; then
    write_result "EG-NACOS-011" "PASS"
else
    write_result "EG-NACOS-011" "SKIP" "Nacos 控制台不可达 (HTTP $nacos_http_code)"
fi

# ========== EG-NACOS-012 ==========
write_result "EG-NACOS-012" "SKIP" "gRPC 长连接拦截需要 gRPC 协议支持，当前 Agent 仅配置 HTTP"

# 恢复 stub
switch_env_mode "$NACOS_ENV_ID" "stub" 2>/dev/null || true

echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "  测试结果: $PASSED 通过, $FAILED 失败, $SKIPPED 跳过${NC}"
echo -e "${CYAN}============================================${NC}"

export_junit_xml "$SCRIPT_DIR/junit-report.xml"

if [[ $FAILED -gt 0 ]]; then exit 1; elif [[ $SKIPPED -gt 0 ]]; then exit 2; fi
exit 0
