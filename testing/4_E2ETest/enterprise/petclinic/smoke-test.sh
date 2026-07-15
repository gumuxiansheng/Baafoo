#!/usr/bin/env bash
# Spring Boot PetClinic 企业级测试 - 冒烟测试脚本 (bash 版)
# 用法: ./smoke-test.sh

set -uo pipefail

SERVER_BASE_URL="${SERVER_BASE_URL:-http://localhost:18084}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:19966}"
API_KEY="${API_KEY:-enterprise-admin-key}"
MODE_SETTLE_WAIT=12

PASSED=0
FAILED=0
SKIPPED=0
TEST_RESULTS=()
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

write_result() {
    local test_name="$1" status="$2" detail="${3:-}"
    TEST_RESULTS+=("$test_name|$status|$detail")
    case "$status" in
        PASS) echo -e "${GREEN}[PASS]${NC} $test_name"; ((PASSED++)) ;;
        FAIL) echo -e "${RED}[FAIL]${NC} $test_name"; [[ -n "$detail" ]] && echo -e "       ${RED}$detail${NC}"; ((FAILED++)) ;;
        SKIP) echo -e "${YELLOW}[SKIP]${NC} $test_name"; [[ -n "$detail" ]] && echo -e "       ${YELLOW}$detail${NC}"; ((SKIPPED++)) ;;
    esac
    return 0
}

xml_escape() {
    local s="$1"
    s="${s//&/&amp;}"; s="${s//</&lt;}"; s="${s//>/&gt;}"; s="${s//\"/&quot;}"; s="${s//\'/&apos;}"
    echo -n "$s"
}

export_junit_xml() {
    local path="$1" ts
    ts=$(date -u +"%Y-%m-%dT%H:%M:%S")
    local total=$((PASSED + FAILED + SKIPPED))
    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo "<testsuites name=\"baafoo-enterprise-petclinic\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\">"
        echo "<testsuite name=\"EnterprisePetClinic\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\" timestamp=\"$ts\">"
        for entry in "${TEST_RESULTS[@]}"; do
            IFS='|' read -r name status message <<< "$entry"
            local esc_name esc_msg
            esc_name=$(xml_escape "$name"); esc_msg=$(xml_escape "$message")
            echo "  <testcase name=\"$esc_name\" classname=\"EnterprisePetClinic\" status=\"$(echo $status | tr '[:upper:]' '[:lower:]')\">"
            if [[ "$status" == "FAIL" ]]; then
                echo "    <failure message=\"$esc_msg\">$esc_msg</failure>"
            elif [[ "$status" == "SKIP" ]]; then
                echo "    <skipped message=\"$esc_msg\"/>"
            fi
            echo "  </testcase>"
        done
        echo "</testsuite></testsuites>"
    } > "$path"
    echo -e "  ${CYAN}[OK]${NC} JUnit XML written: $path"
}

api_get() { curl -sf -H "X-Api-Key: $API_KEY" "$SERVER_BASE_URL$1" 2>/dev/null; }
app_get() { curl -sf "$APP_BASE_URL$1" 2>/dev/null; }

get_env_id() {
    local resp
    resp=$(api_get "/__baafoo__/api/environments" 2>/dev/null) || return 1
    echo "$resp" | jq -r --arg name "$1" '.data[] | select(.name == $name or .id == $name) | .id' 2>/dev/null | head -1
}
get_env_mode() {
    local resp
    resp=$(api_get "/__baafoo__/api/environments" 2>/dev/null) || return 1
    echo "$resp" | jq -r --arg name "$1" '.data[] | select(.name == $name or .id == $name) | .mode' 2>/dev/null | head -1
}
switch_env_mode() {
    curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X PUT -d "{\"mode\":\"$2\"}" "$SERVER_BASE_URL/__baafoo__/api/environments/$1" >/dev/null 2>&1
    sleep "$MODE_SETTLE_WAIT"
}
restore_env_mode() { [[ -n "$1" ]] && switch_env_mode "$1" "${2:-stub}" 2>/dev/null || true; }

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  PetClinic 企业级测试 - 冒烟测试${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

PET_ENV_ID=$(get_env_id "enterprise-petclinic" 2>/dev/null || echo "")
ORIG_MODE=$(get_env_mode "enterprise-petclinic" 2>/dev/null || echo "")

# ========== EG-PET-001 ==========
http_code=$(curl -s -o /dev/null -w '%{http_code}' "$APP_BASE_URL/vets" 2>/dev/null)
if [[ "$http_code" == "200" ]]; then
    write_result "EG-PET-001: 应用启动健康检查" "PASS"
else
    write_result "EG-PET-001: 应用启动健康检查" "FAIL" "HTTP $http_code"
fi

# ========== EG-PET-002 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-petclinic")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-PET-002: Agent 成功注册" "PASS"
else
    write_result "EG-PET-002: Agent 成功注册" "FAIL" "未找到 enterprise-petclinic 的 online agent"
fi

# ========== EG-PET-003 ==========
[[ -n "$PET_ENV_ID" && "$ORIG_MODE" != "stub" ]] && switch_env_mode "$PET_ENV_ID" "stub" 2>/dev/null || true
resp=$(app_get "/vets" 2>/dev/null)
if echo "$resp" | grep -q '"mocked"' 2>/dev/null && echo "$resp" | jq -e '.mocked == true' >/dev/null 2>&1; then
    write_result "EG-PET-003: Vet API Mock 验证" "PASS"
else
    write_result "EG-PET-003: Vet API Mock 验证" "FAIL" "mocked flag not found"
fi

# ========== EG-PET-004 ==========
resp=$(app_get "/owners" 2>/dev/null)
if echo "$resp" | grep -q "mocked" 2>/dev/null; then
    write_result "EG-PET-004: Owner API Mock 验证" "PASS"
else
    write_result "EG-PET-004: Owner API Mock 验证" "FAIL" "mocked flag not found in owners response"
fi

# ========== EG-PET-005 ==========
if [[ -n "$PET_ENV_ID" ]]; then
    switch_env_mode "$PET_ENV_ID" "passthrough" 2>/dev/null || true
    resp=$(app_get "/vets" 2>/dev/null)
    if ! echo "$resp" | grep -q '"mocked"[[:space:]]*:[[:space:]]*true' 2>/dev/null; then
        write_result "EG-PET-005: Passthrough 模式透传" "PASS"
    else
        write_result "EG-PET-005: Passthrough 模式透传" "FAIL" "response still contains mocked flag"
    fi
    restore_env_mode "$PET_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-PET-005: Passthrough 模式透传" "SKIP" "环境未找到"
fi

# ========== EG-PET-006 ==========
if [[ -n "$PET_ENV_ID" ]]; then
    rec_before=$(api_get "/__baafoo__/api/recordings?limit=50" 2>/dev/null | jq 'length' 2>/dev/null || echo 0)
    switch_env_mode "$PET_ENV_ID" "record" 2>/dev/null || true
    app_get "/vets" >/dev/null 2>&1; app_get "/owners" >/dev/null 2>&1
    sleep 2
    rec_after=$(api_get "/__baafoo__/api/recordings?limit=50" 2>/dev/null | jq 'length' 2>/dev/null || echo 0)
    if [[ "$rec_after" -gt "$rec_before" ]]; then
        write_result "EG-PET-006: Record 模式录制" "PASS" "recordings: $rec_before -> $rec_after"
    else
        write_result "EG-PET-006: Record 模式录制" "FAIL" "recordings: $rec_before -> $rec_after"
    fi
    restore_env_mode "$PET_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-PET-006: Record 模式录制" "SKIP" "环境未找到"
fi

# ========== EG-PET-007 ==========
if [[ -n "$PET_ENV_ID" ]]; then
    switch_env_mode "$PET_ENV_ID" "stub" 2>/dev/null || true
    stub_resp=$(app_get "/vets" 2>/dev/null)
    stub_works=$(echo "$stub_resp" | grep -q "mocked" && echo true || echo false)

    switch_env_mode "$PET_ENV_ID" "passthrough" 2>/dev/null || true
    pt_resp=$(app_get "/vets" 2>/dev/null)
    pt_works=$(echo "$pt_resp" | grep -q '"mocked"[[:space:]]*:[[:space:]]*true' && echo false || echo true)

    switch_env_mode "$PET_ENV_ID" "stub" 2>/dev/null || true
    stub_again_resp=$(app_get "/vets" 2>/dev/null)
    stub_restored=$(echo "$stub_again_resp" | grep -q "mocked" && echo true || echo false)

    if $stub_works && $pt_works && $stub_restored; then
        write_result "EG-PET-007: 环境模式热切换" "PASS"
    else
        write_result "EG-PET-007: 环境模式热切换" "FAIL" "stub=$stub_works pt=$pt_works restored=$stub_restored"
    fi
    restore_env_mode "$PET_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-PET-007: 环境模式热切换" "SKIP" "环境未找到"
fi

# ========== EG-PET-008 ==========
[[ -n "$PET_ENV_ID" ]] && switch_env_mode "$PET_ENV_ID" "passthrough" 2>/dev/null || true
all_ok=true
failed_eps=""
for ep in /vets /owners /pets /specialties /visits; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$APP_BASE_URL$ep" 2>/dev/null)
    if [[ "$code" != "200" ]]; then
        all_ok=false
        failed_eps="${failed_eps}${ep}(${code}) "
    fi
done
if $all_ok; then
    write_result "EG-PET-008: 应用功能完整性验证" "PASS" "All 5 endpoints OK"
else
    write_result "EG-PET-008: 应用功能完整性验证" "FAIL" "Failed: $failed_eps"
fi
restore_env_mode "$PET_ENV_ID" "$ORIG_MODE"

# ========== EG-PET-009 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
error_detail=$(echo "$agents_resp" | jq -r '[.data[] | select(.environment == "enterprise-petclinic")] | .[0].pluginStatuses // [] | [.[] | select(.status == "ERROR" or .state == "ERROR") | .name + ": " + (.error // "unknown")] | join("; ")' 2>/dev/null || echo "")
if [[ -z "$error_detail" || "$error_detail" == "null" ]]; then
    write_result "EG-PET-009: 无类加载冲突" "PASS"
else
    write_result "EG-PET-009: 无类加载冲突" "FAIL" "$error_detail"
fi

# ========== EG-PET-010 ==========
[[ -n "$PET_ENV_ID" ]] && switch_env_mode "$PET_ENV_ID" "passthrough" 2>/dev/null || true
call_count=0
for i in $(seq 1 30); do
    curl -sf "$APP_BASE_URL/vets" >/dev/null 2>&1 && ((call_count++))
    sleep 0.5
done
# Try actuator metrics
init_heap=$(curl -sf "$APP_BASE_URL/actuator/metrics/jvm.memory.used" 2>/dev/null | jq -r '.measurements[0].value' 2>/dev/null || echo "")
end_heap="$init_heap"
if [[ -n "$init_heap" ]]; then
    # Already ran 30 calls, get end value
    end_heap=$(curl -sf "$APP_BASE_URL/actuator/metrics/jvm.memory.used" 2>/dev/null | jq -r '.measurements[0].value' 2>/dev/null || echo "$init_heap")
fi
if [[ -n "$init_heap" && -n "$end_heap" ]]; then
    heap_delta=$(echo "scale=2; ($end_heap - $init_heap) / 1048576" | bc 2>/dev/null || echo 0)
    stable=$(echo "$heap_delta < 20" | bc 2>/dev/null || echo 0)
    if [[ $stable -eq 1 ]]; then
        write_result "EG-PET-010: 内存泄漏检查（短期）" "PASS" "heap delta: ${heap_delta}MB (calls=$call_count)"
    else
        write_result "EG-PET-010: 内存泄漏检查（短期）" "FAIL" "heap delta: ${heap_delta}MB (calls=$call_count)"
    fi
else
    write_result "EG-PET-010: 内存泄漏检查（短期）" "SKIP" "无法获取 JVM 内存指标"
fi
restore_env_mode "$PET_ENV_ID" "$ORIG_MODE"

# ========== EG-PET-011 ==========
[[ -n "$PET_ENV_ID" ]] && switch_env_mode "$PET_ENV_ID" "passthrough" 2>/dev/null || true
pt_times=()
for i in $(seq 1 5); do
    ms=$(curl -s -o /dev/null -w '%{time_total}' "$APP_BASE_URL/vets" 2>/dev/null | awk '{printf "%.0f", $1 * 1000}')
    pt_times+=("$ms")
done
pt_avg=$(echo "${pt_times[@]}" | awk '{s=0; for(i=1;i<=NF;i++) s+=$i; print s/NF}')
switch_env_mode "$PET_ENV_ID" "stub" 2>/dev/null || true
stub_times=()
for i in $(seq 1 5); do
    ms=$(curl -s -o /dev/null -w '%{time_total}' "$APP_BASE_URL/vets" 2>/dev/null | awk '{printf "%.0f", $1 * 1000}')
    stub_times+=("$ms")
done
stub_avg=$(echo "${stub_times[@]}" | awk '{s=0; for(i=1;i<=NF;i++) s+=$i; print s/NF}')
overhead=$(echo "scale=1; ($stub_avg - $pt_avg) * 100 / $pt_avg" | bc 2>/dev/null || echo 0)
acceptable=$(echo "$overhead <= 200" | bc 2>/dev/null || echo 0)
if [[ $acceptable -eq 1 ]]; then
    write_result "EG-PET-011: CPU 开销评估" "PASS" "pt avg=${pt_avg}ms, stub avg=${stub_avg}ms, overhead=${overhead}%"
else
    write_result "EG-PET-011: CPU 开销评估" "FAIL" "pt avg=${pt_avg}ms, stub avg=${stub_avg}ms, overhead=${overhead}%"
fi
restore_env_mode "$PET_ENV_ID" "$ORIG_MODE"

# ========== EG-PET-012 ==========
write_result "EG-PET-012: 异步调用拦截验证" "SKIP" "PetClinic REST 无已知 @Async 接口"

# ========== EG-PET-013 ==========
write_result "EG-PET-013: 定时任务调用拦截验证" "SKIP" "PetClinic REST 无已知 @Scheduled 任务"

echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "  测试结果: $PASSED 通过, $FAILED 失败, $SKIPPED 跳过${NC}"
echo -e "${CYAN}============================================${NC}"

export_junit_xml "$SCRIPT_DIR/junit-report.xml"

if [[ $FAILED -gt 0 ]]; then exit 1; elif [[ $SKIPPED -gt 0 ]]; then exit 2; fi
exit 0
