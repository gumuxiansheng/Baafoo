#!/usr/bin/env bash
# Spring Cloud Alibaba 企业级测试 - 冒烟测试脚本 (bash 版)
# 用法: ./smoke-test.sh

set -uo pipefail

SERVER_BASE_URL="${SERVER_BASE_URL:-http://localhost:18084}"
PROVIDER_BASE_URL="${PROVIDER_BASE_URL:-http://localhost:18081}"
CONSUMER_BASE_URL="${CONSUMER_BASE_URL:-http://localhost:18083}"
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
      echo "<testsuites name=\"baafoo-enterprise-sca\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\">"
      echo "<testsuite name=\"EnterpriseSCA\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\" timestamp=\"$ts\">"
      for entry in "${TEST_RESULTS[@]}"; do
          IFS='|' read -r name status message <<< "$entry"
          local en em; en=$(xml_escape "$name"); em=$(xml_escape "$message")
          echo "  <testcase name=\"$en\" classname=\"EnterpriseSCA\" status=\"$(echo $status | tr '[:upper:]' '[:lower:]')\">"
          [[ "$status" == "FAIL" ]] && echo "    <failure message=\"$em\">$em</failure>"
          [[ "$status" == "SKIP" ]] && echo "    <skipped message=\"$em\"/>"
          echo "  </testcase>"
      done
      echo "</testsuite></testsuites>"; } > "$path"
    echo -e "  ${CYAN}[OK]${NC} JUnit XML written: $path"
}

api_get() { curl -sf -H "X-Api-Key: $API_KEY" "$SERVER_BASE_URL$1" 2>/dev/null; }
provider_get() { curl -sf "$PROVIDER_BASE_URL$1" 2>/dev/null; }
consumer_get() { curl -sf "$CONSUMER_BASE_URL$1" 2>/dev/null; }
get_env_id() { api_get "/__baafoo__/api/environments" 2>/dev/null | jq -r --arg name "$1" '.data[] | select(.name == $name or .id == $name) | .id' 2>/dev/null | head -1; }
get_env_mode() { api_get "/__baafoo__/api/environments" 2>/dev/null | jq -r --arg name "$1" '.data[] | select(.name == $name or .id == $name) | .mode' 2>/dev/null | head -1; }
switch_env_mode() { curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X PUT -d "{\"mode\":\"$2\"}" "$SERVER_BASE_URL/__baafoo__/api/environments/$1" >/dev/null 2>&1; sleep "$MODE_SETTLE_WAIT"; }
restore_env_mode() { [[ -n "$1" ]] && switch_env_mode "$1" "${2:-stub}" 2>/dev/null || true; }

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Spring Cloud Alibaba 企业级测试 - 冒烟测试${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

CONSUMER_ENV_ID=$(get_env_id "enterprise-sca-consumer" 2>/dev/null || echo "")
ORIG_MODE=$(get_env_mode "enterprise-sca-consumer" 2>/dev/null || echo "")

# ========== EG-SCA-001 ==========
code=$(curl -s -o /dev/null -w '%{http_code}' "$PROVIDER_BASE_URL/actuator/health" 2>/dev/null)
[[ "$code" != "200" ]] && code=$(curl -s -o /dev/null -w '%{http_code}' "$PROVIDER_BASE_URL/health" 2>/dev/null)
[[ "$code" == "200" ]] && write_result "EG-SCA-001: Provider 应用健康检查" "PASS" || write_result "EG-SCA-001: Provider 应用健康检查" "FAIL" "HTTP $code"

# ========== EG-SCA-002 ==========
code=$(curl -s -o /dev/null -w '%{http_code}' "$CONSUMER_BASE_URL/actuator/health" 2>/dev/null)
[[ "$code" != "200" ]] && code=$(curl -s -o /dev/null -w '%{http_code}' "$CONSUMER_BASE_URL/health" 2>/dev/null)
[[ "$code" == "200" ]] && write_result "EG-SCA-002: Consumer 应用健康检查" "PASS" || write_result "EG-SCA-002: Consumer 应用健康检查" "FAIL" "HTTP $code"

# ========== EG-SCA-003 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-sca-provider")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-SCA-003: Provider Agent 注册验证" "PASS"
else
    write_result "EG-SCA-003: Provider Agent 注册验证" "FAIL" "未找到 enterprise-sca-provider 的 online agent"
fi

# ========== EG-SCA-004 ==========
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-sca-consumer")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-SCA-004: Consumer Agent 注册验证" "PASS"
else
    write_result "EG-SCA-004: Consumer Agent 注册验证" "FAIL" "未找到 enterprise-sca-consumer 的 online agent"
fi

# ========== EG-SCA-005 ==========
resp=$(consumer_get "/services" 2>/dev/null)
if echo "$resp" | grep -q "service-provider" 2>/dev/null; then
    write_result "EG-SCA-005: Nacos 服务发现验证" "PASS"
else
    write_result "EG-SCA-005: Nacos 服务发现验证" "FAIL" "service-provider not in discovered services"
fi

# ========== EG-SCA-006 ==========
[[ -n "$CONSUMER_ENV_ID" && "$ORIG_MODE" != "stub" ]] && switch_env_mode "$CONSUMER_ENV_ID" "stub" 2>/dev/null || true
resp=$(consumer_get "/echo-feign/hello" 2>/dev/null)
if echo "$resp" | grep -qi "mock" 2>/dev/null; then
    write_result "EG-SCA-006: Feign 调用 Mock 拦截" "PASS"
else
    write_result "EG-SCA-006: Feign 调用 Mock 拦截" "FAIL" "response: $resp"
fi

# ========== EG-SCA-007 ==========
if [[ -n "$CONSUMER_ENV_ID" ]]; then
    switch_env_mode "$CONSUMER_ENV_ID" "passthrough" 2>/dev/null || true
    resp=$(consumer_get "/echo-feign/hello" 2>/dev/null)
    if echo "$resp" | grep -q "hello Nacos Discovery" 2>/dev/null && ! echo "$resp" | grep -qi "mock" 2>/dev/null; then
        write_result "EG-SCA-007: Passthrough 模式透传" "PASS"
    else
        write_result "EG-SCA-007: Passthrough 模式透传" "FAIL" "response: $resp"
    fi
    restore_env_mode "$CONSUMER_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-SCA-007: Passthrough 模式透传" "SKIP" "环境未找到"
fi

# ========== EG-SCA-008 ==========
if [[ -n "$CONSUMER_ENV_ID" ]]; then
    rec_before=$(api_get "/__baafoo__/api/recordings?limit=50" 2>/dev/null | jq 'length' 2>/dev/null || echo 0)
    switch_env_mode "$CONSUMER_ENV_ID" "record" 2>/dev/null || true
    consumer_get "/echo-feign/record-test" >/dev/null 2>&1; consumer_get "/echo-feign/record-test-2" >/dev/null 2>&1
    sleep 2
    rec_after=$(api_get "/__baafoo__/api/recordings?limit=50" 2>/dev/null | jq 'length' 2>/dev/null || echo 0)
    [[ "$rec_after" -gt "$rec_before" ]] && write_result "EG-SCA-008: Record 模式录制" "PASS" "recordings: $rec_before -> $rec_after" || write_result "EG-SCA-008: Record 模式录制" "FAIL" "recordings: $rec_before -> $rec_after"
    restore_env_mode "$CONSUMER_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-SCA-008: Record 模式录制" "SKIP" "环境未找到"
fi

# ========== EG-SCA-009 ==========
if [[ -n "$CONSUMER_ENV_ID" ]]; then
    switch_env_mode "$CONSUMER_ENV_ID" "stub" 2>/dev/null || true
    stub_resp=$(consumer_get "/echo-feign/hotswap" 2>/dev/null); stub_works=$(echo "$stub_resp" | grep -qi "mock" && echo true || echo false)
    switch_env_mode "$CONSUMER_ENV_ID" "passthrough" 2>/dev/null || true
    pt_resp=$(consumer_get "/echo-feign/hotswap" 2>/dev/null); pt_works=$(echo "$pt_resp" | grep -q "hello Nacos Discovery" && ! echo "$pt_resp" | grep -qi "mock" && echo true || echo false)
    switch_env_mode "$CONSUMER_ENV_ID" "stub" 2>/dev/null || true
    stub_again_resp=$(consumer_get "/echo-feign/hotswap2" 2>/dev/null); stub_restored=$(echo "$stub_again_resp" | grep -qi "mock" && echo true || echo false)
    if $stub_works && $pt_works && $stub_restored; then
        write_result "EG-SCA-009: 环境模式热切换" "PASS"
    else
        write_result "EG-SCA-009: 环境模式热切换" "FAIL" "stub=$stub_works pt=$pt_works restored=$stub_restored"
    fi
    restore_env_mode "$CONSUMER_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-SCA-009: 环境模式热切换" "SKIP" "环境未找到"
fi

# ========== EG-SCA-010 ==========
echo_resp=$(provider_get "/echo/direct-test" 2>/dev/null)
echo_ok=$(echo "$echo_resp" | grep -q "hello Nacos Discovery direct-test" && echo true || echo false)
divide_resp=$(provider_get "/divide?a=10&b=2" 2>/dev/null)
divide_ok=$(echo "$divide_resp" | grep -q "5" && echo true || echo false)
if $echo_ok && $divide_ok; then
    write_result "EG-SCA-010: Provider 功能完整性" "PASS"
else
    write_result "EG-SCA-010: Provider 功能完整性" "FAIL" "echo=$echo_ok divide=$divide_ok"
fi

# ========== EG-SCA-011 ==========
[[ -n "$CONSUMER_ENV_ID" ]] && switch_env_mode "$CONSUMER_ENV_ID" "passthrough" 2>/dev/null || true
resp=$(consumer_get "/divide-feign?a=20&b=4" 2>/dev/null)
if echo "$resp" | grep -q "5" 2>/dev/null; then
    write_result "EG-SCA-011: Feign divide 调用验证" "PASS"
else
    write_result "EG-SCA-011: Feign divide 调用验证" "FAIL" "20/4 = $resp (expected 5)"
fi
restore_env_mode "$CONSUMER_ENV_ID" "$ORIG_MODE"

# ========== EG-SCA-012 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
error_detail=$(echo "$agents_resp" | jq -r '[.data[] | select(.environment | test("enterprise-sca"))] | [.[] | .pluginStatuses // [] | .[] | select(.status == "ERROR" or .state == "ERROR") | .name + ": " + (.error // "unknown")] | join("; ")' 2>/dev/null || echo "")
if [[ -z "$error_detail" || "$error_detail" == "null" ]]; then
    write_result "EG-SCA-012: 无类加载冲突" "PASS"
else
    write_result "EG-SCA-012: 无类加载冲突" "FAIL" "$error_detail"
fi

echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "  测试结果: $PASSED 通过, $FAILED 失败, $SKIPPED 跳过${NC}"
echo -e "${CYAN}============================================${NC}"

export_junit_xml "$SCRIPT_DIR/junit-report.xml"

if [[ $FAILED -gt 0 ]]; then exit 1; elif [[ $SKIPPED -gt 0 ]]; then exit 2; fi
exit 0
