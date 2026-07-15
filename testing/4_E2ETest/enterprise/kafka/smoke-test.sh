#!/usr/bin/env bash
# Kafka 企业级测试 - 冒烟测试脚本 (bash 版)
# 用法: ./smoke-test.sh

set -uo pipefail

SERVER_BASE_URL="${SERVER_BASE_URL:-http://localhost:18084}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:18090}"
API_KEY="${API_KEY:-enterprise-admin-key}"
MODE_SETTLE_WAIT=12

PASSED=0
FAILED=0
SKIPPED=0
TEST_RESULTS=()
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 颜色
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
    s="${s//&/&amp;}"
    s="${s//</&lt;}"
    s="${s//>/&gt;}"
    s="${s//\"/&quot;}"
    s="${s//\'/&apos;}"
    echo -n "$s"
}

export_junit_xml() {
    local path="$1"
    local ts
    ts=$(date -u +"%Y-%m-%dT%H:%M:%S")
    local total=$((PASSED + FAILED + SKIPPED))

    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo "<testsuites name=\"baafoo-enterprise-kafka\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\">"
        echo "<testsuite name=\"EnterpriseKafka\" tests=\"$total\" failures=\"$FAILED\" skipped=\"$SKIPPED\" errors=\"0\" timestamp=\"$ts\">"
        for entry in "${TEST_RESULTS[@]}"; do
            IFS='|' read -r name status message <<< "$entry"
            local esc_name esc_msg
            esc_name=$(xml_escape "$name")
            esc_msg=$(xml_escape "$message")
            echo "  <testcase name=\"$esc_name\" classname=\"EnterpriseKafka\" status=\"$(echo $status | tr '[:upper:]' '[:lower:]')\">"
            if [[ "$status" == "FAIL" ]]; then
                echo "    <failure message=\"$esc_msg\">$esc_msg</failure>"
            elif [[ "$status" == "SKIP" ]]; then
                echo "    <skipped message=\"$esc_msg\"/>"
            fi
            echo "  </testcase>"
        done
        echo "</testsuite>"
        echo "</testsuites>"
    } > "$path"
    echo -e "  ${CYAN}[OK]${NC} JUnit XML written: $path"
}

# API helpers
api_get() {
    curl -sf -H "X-Api-Key: $API_KEY" "$SERVER_BASE_URL$1" 2>/dev/null
}

api_post() {
    curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X POST -d "$2" "$SERVER_BASE_URL$1" 2>/dev/null
}

app_get() {
    curl -sf "$APP_BASE_URL$1" 2>/dev/null
}

get_env_id() {
    local env_name="$1"
    local resp
    resp=$(api_get "/__baafoo__/api/environments" 2>/dev/null) || return 1
    echo "$resp" | jq -r --arg name "$env_name" '.data[] | select(.name == $name or .id == $name) | .id' 2>/dev/null | head -1
}

get_env_mode() {
    local env_name="$1"
    local resp
    resp=$(api_get "/__baafoo__/api/environments" 2>/dev/null) || return 1
    echo "$resp" | jq -r --arg name "$env_name" '.data[] | select(.name == $name or .id == $name) | .mode' 2>/dev/null | head -1
}

switch_env_mode() {
    local env_id="$1" mode="$2"
    curl -sf -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" -X PUT -d "{\"mode\":\"$mode\"}" "$SERVER_BASE_URL/__baafoo__/api/environments/$env_id" >/dev/null 2>&1
    sleep "$MODE_SETTLE_WAIT"
}

restore_env_mode() {
    local env_id="$1" mode="${2:-stub}"
    [[ -n "$env_id" ]] && switch_env_mode "$env_id" "$mode" 2>/dev/null || true
}

echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Kafka 企业级测试 - 冒烟测试${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""
echo "Server: $SERVER_BASE_URL"
echo "App:    $APP_BASE_URL"
echo ""

# ========== EG-KAFKA-001: 应用健康检查 ==========
resp=$(app_get "/api/stub-demo/health" 2>/dev/null)
if [[ "$resp" == "OK" ]]; then
    write_result "EG-KAFKA-001: 应用启动健康检查" "PASS"
else
    write_result "EG-KAFKA-001: 应用启动健康检查" "FAIL" "响应: ${resp:-空}"
fi

# ========== EG-KAFKA-002: Agent 注册验证 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
if echo "$agents_resp" | jq -e '[.data[] | select(.environment == "enterprise-kafka")] | length > 0' >/dev/null 2>&1; then
    write_result "EG-KAFKA-002: Agent 成功注册" "PASS"
else
    write_result "EG-KAFKA-002: Agent 成功注册" "FAIL" "未找到 environment=enterprise-kafka 的 online agent"
fi

# ========== EG-KAFKA-003: Producer 消息发送 Mock ==========
resp=$(app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-test-topic&message=hello-enterprise-smoke-test" 2>/dev/null)
if echo "$resp" | jq -e '.success == true' >/dev/null 2>&1; then
    write_result "EG-KAFKA-003: Kafka Producer Mock" "PASS"
else
    write_result "EG-KAFKA-003: Kafka Producer Mock" "FAIL" "success=$(echo "$resp" | jq -r '.success' 2>/dev/null || echo 'N/A')"
fi

# ========== EG-KAFKA-004: Consumer 消息消费 Mock ==========
resp=$(app_get "/api/kafka/consume?bootstrapServers=kafka:9092&topic=enterprise-test-topic" 2>/dev/null)
if echo "$resp" | jq -e '.success == true' >/dev/null 2>&1; then
    write_result "EG-KAFKA-004: Kafka Consumer Mock" "PASS"
else
    write_result "EG-KAFKA-004: Kafka Consumer Mock" "FAIL" "success=$(echo "$resp" | jq -r '.success' 2>/dev/null || echo 'N/A')"
fi

# ========== EG-KAFKA-005: Topic 通配符匹配 ==========
wildcard_matched=false
resp=$(app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-wildcard-test&message=wildcard-test" 2>/dev/null)
if echo "$resp" | jq -e '.success == true' >/dev/null 2>&1; then
    consume_resp=$(app_get "/api/kafka/consume?bootstrapServers=kafka:9092&topic=enterprise-wildcard-test" 2>/dev/null)
    if echo "$consume_resp" | grep -q "wildcard" 2>/dev/null; then
        wildcard_matched=true
    fi
fi
if $wildcard_matched; then
    write_result "EG-KAFKA-005: Topic 通配符匹配" "PASS"
else
    write_result "EG-KAFKA-005: Topic 通配符匹配" "FAIL" "topic=enterprise-wildcard-test"
fi

# ========== EG-KAFKA-006: Passthrough 模式透传真实 Kafka ==========
KAFKA_ENV_ID=$(get_env_id "enterprise-kafka" 2>/dev/null || echo "")
ORIG_MODE=$(get_env_mode "enterprise-kafka" 2>/dev/null || echo "")
if [[ -n "$KAFKA_ENV_ID" ]]; then
    switch_env_mode "$KAFKA_ENV_ID" "passthrough" 2>/dev/null || true
    resp=$(app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-pt-test&message=pt-test-msg" 2>/dev/null)
    if echo "$resp" | jq -e '.success == true' >/dev/null 2>&1; then
        write_result "EG-KAFKA-006: Passthrough 模式透传" "PASS"
    else
        write_result "EG-KAFKA-006: Passthrough 模式透传" "FAIL" "success=$(echo "$resp" | jq -r '.success' 2>/dev/null || echo 'N/A')"
    fi
    restore_env_mode "$KAFKA_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-KAFKA-006: Passthrough 模式透传" "SKIP" "环境 enterprise-kafka 未找到"
fi

# ========== EG-KAFKA-007: Record 模式录制 ==========
if [[ -n "$KAFKA_ENV_ID" ]]; then
    rec_before=$(api_get "/__baafoo__/api/recordings?limit=50" 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    switch_env_mode "$KAFKA_ENV_ID" "record" 2>/dev/null || true
    app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-record-test&message=record-test-msg" >/dev/null 2>&1
    sleep 2
    rec_after=$(api_get "/__baafoo__/api/recordings?limit=50" 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    if [[ "$rec_after" -gt "$rec_before" ]]; then
        write_result "EG-KAFKA-007: Record 模式录制" "PASS" "recordings: $rec_before -> $rec_after"
    else
        write_result "EG-KAFKA-007: Record 模式录制" "FAIL" "recordings: $rec_before -> $rec_after"
    fi
    restore_env_mode "$KAFKA_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-KAFKA-007: Record 模式录制" "SKIP" "环境 enterprise-kafka 未找到"
fi

# ========== EG-KAFKA-008: 环境模式热切换 ==========
if [[ -n "$KAFKA_ENV_ID" ]]; then
    switch_env_mode "$KAFKA_ENV_ID" "stub" 2>/dev/null || true
    stub_resp=$(app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-hotswap-test&message=hotswap-1" 2>/dev/null)
    stub_works=$(echo "$stub_resp" | jq -e '.success == true' >/dev/null 2>&1 && echo true || echo false)

    switch_env_mode "$KAFKA_ENV_ID" "passthrough" 2>/dev/null || true
    pt_resp=$(app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-hotswap-test&message=hotswap-2" 2>/dev/null)
    pt_works=$(echo "$pt_resp" | jq -e '.success == true' >/dev/null 2>&1 && echo true || echo false)

    switch_env_mode "$KAFKA_ENV_ID" "stub" 2>/dev/null || true
    stub_again_resp=$(app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-hotswap-test&message=hotswap-3" 2>/dev/null)
    stub_restored=$(echo "$stub_again_resp" | jq -e '.success == true' >/dev/null 2>&1 && echo true || echo false)

    if $stub_works && $pt_works && $stub_restored; then
        write_result "EG-KAFKA-008: 环境模式热切换" "PASS"
    else
        write_result "EG-KAFKA-008: 环境模式热切换" "FAIL" "stub=$stub_works pt=$pt_works restored=$stub_restored"
    fi
    restore_env_mode "$KAFKA_ENV_ID" "$ORIG_MODE"
else
    write_result "EG-KAFKA-008: 环境模式热切换" "SKIP" "环境 enterprise-kafka 未找到"
fi

# ========== EG-KAFKA-009: 无类加载冲突 ==========
agents_resp=$(api_get "/__baafoo__/api/agents" 2>/dev/null)
error_detail=$(echo "$agents_resp" | jq -r '[.data[] | select(.environment == "enterprise-kafka")] | .[0].pluginStatuses // [] | [.[] | select(.status == "ERROR" or .state == "ERROR") | .name + ": " + (.error // "unknown")] | join("; ")' 2>/dev/null || echo "")
if [[ -z "$error_detail" || "$error_detail" == "null" ]]; then
    write_result "EG-KAFKA-009: 无类加载冲突" "PASS"
else
    write_result "EG-KAFKA-009: 无类加载冲突" "FAIL" "$error_detail"
fi

# ========== EG-KAFKA-010: 高吞吐下 Agent 稳定性 ==========
msg_count=0
fail_count=0
batch_size=50
batch_num=6
for batch in $(seq 0 $((batch_num - 1))); do
    for i in $(seq 0 $((batch_size - 1))); do
        if app_get "/api/kafka/send?bootstrapServers=kafka:9092&topic=enterprise-throughput-test&message=throughput-test-batch${batch}-msg${i}" >/dev/null 2>&1; then
            ((msg_count++))
        else
            ((fail_count++))
        fi
    done
    sleep 0.2
done
total=$((msg_count + fail_count))
if [[ $total -gt 0 ]]; then
    success_rate=$(echo "scale=1; $msg_count * 100 / $total" | bc 2>/dev/null || echo 0)
else
    success_rate=0
fi
stable=$(echo "$success_rate >= 95" | bc 2>/dev/null || echo 0)
if [[ $stable -eq 1 && $fail_count -lt 20 ]]; then
    write_result "EG-KAFKA-010: 高吞吐下 Agent 稳定性" "PASS" "sent=$msg_count failed=$fail_count rate=${success_rate}%"
else
    write_result "EG-KAFKA-010: 高吞吐下 Agent 稳定性" "FAIL" "sent=$msg_count failed=$fail_count rate=${success_rate}%"
fi

# ========== EG-KAFKA-011: Kafka 多版本客户端兼容 ==========
write_result "EG-KAFKA-011: Kafka 多版本客户端兼容" "SKIP" "当前环境仅有一个 Kafka 客户端版本，需多版本环境支持"

echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "  测试结果: $PASSED 通过, $FAILED 失败, $SKIPPED 跳过${NC}"
echo -e "${CYAN}============================================${NC}"

export_junit_xml "$SCRIPT_DIR/junit-report.xml"

if [[ $FAILED -gt 0 ]]; then
    exit 1
elif [[ $SKIPPED -gt 0 ]]; then
    exit 2
fi
exit 0
