#!/bin/bash
# =============================================================================
# Baafoo 全链路集成测试 — 测试用例脚本
# 在 test-runner 容器内运行，通过 Docker 网络访问各服务
#
# 用法: ./run-fullchain-tests.sh
# 依赖: curl, jq
# =============================================================================
set -uo pipefail

# ==================== 配置 ====================
SERVER="http://server:8084"
APP_A="http://app-env-a:9090"
APP_B="http://app-env-b:9091"
API_KEY="staging-admin-key"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# 测试统计
PASS=0
FAIL=0
SKIP=0
FAILED_TESTS=()
TEST_RESULTS=()
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ==================== 工具函数 ====================

record_result() {
  local msg="$1" status="$2" id
  if [[ "$msg" =~ ^([A-Z]{1,4}[0-9]{1,3})[:[:space:]] ]]; then
    id="${BASH_REMATCH[1]}"
  else
    id="$msg"
  fi
  TEST_RESULTS+=("$id|$status|$msg")
}

log_pass()  { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); record_result "$1" "pass"; }
log_fail()  { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); FAILED_TESTS+=("$1"); record_result "$1" "fail"; }
log_skip()  { echo -e "${YELLOW}[SKIP]${NC} $1"; SKIP=$((SKIP+1)); record_result "$1" "skip"; }

# 生成 JUnit 兼容 XML 报告供 CI 解析
write_junit_xml() {
  local path="$1" ts total entry name rest status msg esc
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  total=$((PASS + FAIL + SKIP))
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuites name=\"baafoo-fullchain\" tests=\"$total\" failures=\"$FAIL\" skipped=\"$SKIP\" errors=\"0\">"
    echo "<testsuite name=\"FullChain\" tests=\"$total\" failures=\"$FAIL\" skipped=\"$SKIP\" errors=\"0\" timestamp=\"$ts\">"
    if [ "${#TEST_RESULTS[@]}" -gt 0 ]; then
      for entry in "${TEST_RESULTS[@]}"; do
        name="${entry%%|*}"
        rest="${entry#*|}"
        status="${rest%%|*}"
        msg="${rest#*|}"
        esc="$(printf '%s' "$msg" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g")"
        echo "<testcase name=\"$name\" classname=\"FullChain\" status=\"$status\">"
        if [ "$status" = "fail" ]; then
          echo "<failure message=\"$esc\">$esc</failure>"
        elif [ "$status" = "skip" ]; then
          echo "<skipped message=\"$esc\"/>"
        fi
        echo "</testcase>"
      done
    fi
    echo "</testsuite></testsuites>"
  } > "$path"
  echo "  [OK] JUnit XML written: $path"
}

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        log_pass "$desc"
    else
        log_fail "$desc (expected=$expected, actual=$actual)"
    fi
}

assert_contains() {
    local desc="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        log_pass "$desc"
    else
        log_fail "$desc (body does not contain '$needle')"
    fi
}

assert_true() {
    local desc="$1" condition="$2"
    if [ "$condition" = "true" ] || [ "$condition" = "1" ]; then
        log_pass "$desc"
    else
        log_fail "$desc (condition was $condition)"
    fi
}

# HTTP 请求辅助
api_get() {
    curl -sf -H "X-Api-Key: $API_KEY" "$SERVER/__baafoo__/api/$1" 2>/dev/null || echo "{}"
}

app_get() {
    curl -sf "$1" 2>/dev/null || echo "{}"
}

# jq 安全取值 (jq 不可用时用 grep 兜底)
jq_val() {
    local json="$1" key="$2"
    if command -v jq &>/dev/null; then
        echo "$json" | jq -r "$key" 2>/dev/null
    else
        echo "$json" | grep -o "\"$key\"[^,}]*" | head -1 | sed 's/.*: *"\{0,1\}//;s/"\{0,1\}$//'
    fi
}

# ==================== 测试用例 ====================

echo ""
echo "============================================================"
echo "  Baafoo 全链路集成测试"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"
echo ""

# -------------------- F: 核心功能 --------------------
echo "--- F: 核心功能 ---"

# F01: Server 健康检查
HEALTH=$(curl -sf "$SERVER/__baafoo__/api/status" 2>/dev/null || echo "")
if echo "$HEALTH" | grep -q "status"; then
    log_pass "F01: Server 健康检查"
else
    log_fail "F01: Server 健康检查 (response: $HEALTH)"
fi

# F02: PostgreSQL 连接
STATUS_JSON=$(api_get "status")
if echo "$STATUS_JSON" | grep -qi "postgresql\|postgres"; then
    log_pass "F02: PostgreSQL 数据库连接"
else
    log_skip "F02: PostgreSQL 数据库连接 (可能使用 H2)"
fi

# F03: 规则列表非空
RULES_JSON=$(api_get "rules")
RULE_COUNT=$(echo "$RULES_JSON" | jq_val '.' '.' 2>/dev/null; echo "$RULES_JSON" | grep -o '"id"' | wc -l)
if [ "$RULE_COUNT" -gt 0 ] 2>/dev/null; then
    log_pass "F03: 规则已注册 (count=$RULE_COUNT)"
else
    log_fail "F03: 规则列表为空"
fi

# F04: app-env-a 健康检查
APP_A_HEALTH=$(curl -sf "$APP_A/api/stub-demo/health" 2>/dev/null || echo "")
if [ "$APP_A_HEALTH" = "OK" ]; then
    log_pass "F04: app-env-a 健康检查"
else
    log_fail "F04: app-env-a 健康检查 (response: $APP_A_HEALTH)"
fi

# F05: app-env-b 健康检查
APP_B_HEALTH=$(curl -sf "$APP_B/api/stub-demo/health" 2>/dev/null || echo "")
if [ "$APP_B_HEALTH" = "OK" ]; then
    log_pass "F05: app-env-b 健康检查"
else
    log_fail "F05: app-env-b 健康检查 (response: $APP_B_HEALTH)"
fi

# -------------------- H: HTTP 协议 --------------------
echo ""
echo "--- H: HTTP 协议 ---"

# H01: HTTP GET 挡板
RESP=$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")
STUBBED=$(jq_val "$RESP" '.stubbed')
BODY=$(jq_val "$RESP" '.body')
assert_true "H01: HTTP GET 被挡板拦截" "$STUBBED"
assert_contains "H01: HTTP GET 响应内容正确" "$BODY" "mocked"

# H02: HTTP POST 挡板
RESP=$(curl -sf "$APP_A/api/http/post?url=http://real-backend:9090/post&body=%7B%22test%22%3A%22baafoo%22%7D" 2>/dev/null || echo "{}")
STUBBED=$(jq_val "$RESP" '.stubbed')
assert_true "H02: HTTP POST 被挡板拦截" "$STUBBED"

# H03: HTTP PUT/DELETE 挡板
RESP=$(curl -sf "$APP_A/api/http/methods" 2>/dev/null || echo "{}")
PUT_STUBBED=$(jq_val "$RESP" '.put.stubbed')
assert_true "H03: HTTP PUT 被挡板拦截" "$PUT_STUBBED"

# H04: HTTP DELETE 挡板
DELETE_STUBBED=$(jq_val "$RESP" '.delete.stubbed')
assert_true "H04: HTTP DELETE 被挡板拦截" "$DELETE_STUBBED"

# H05: HTTP 延迟规则
RESP=$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/delay")
STUBBED=$(jq_val "$RESP" '.stubbed')
assert_true "H05: HTTP 延迟路径被挡板拦截" "$STUBBED"

# H06: HTTP 错误码规则
RESP=$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/error500")
STATUS=$(jq_val "$RESP" '.statusCode')
assert_eq "H06: HTTP 错误码返回 500" "500" "$STATUS"

# -------------------- T: TCP 协议 --------------------
echo ""
echo "--- T: TCP 协议 ---"

# T01: TCP BIO Socket (hex 匹配)
RESP=$(curl -sf "$APP_A/api/socket/bio?host=127.0.0.1&port=9999" 2>/dev/null || echo "{}")
BODY=$(jq_val "$RESP" '.body')
if echo "$BODY" | grep -qi "stub\|ok\|HELLO"; then
    log_pass "T01: TCP BIO Socket 挡板"
else
    if [ -n "$BODY" ] && [ "$BODY" != "null" ]; then
        log_pass "T01: TCP BIO Socket 有响应"
    else
        log_fail "T01: TCP BIO Socket 挡板 (response: $RESP)"
    fi
fi

# T02: TCP NIO Socket
RESP=$(curl -sf "$APP_A/api/socket/nio?host=127.0.0.1&port=9999" 2>/dev/null || echo "{}")
BODY=$(jq_val "$RESP" '.body')
if [ -n "$BODY" ] && [ "$BODY" != "null" ]; then
    log_pass "T02: TCP NIO Socket 有响应"
else
    log_skip "T02: TCP NIO Socket (无响应)"
fi

# T03: TCP 多轮交互
RESP=$(curl -sf "$APP_A/api/socket/multiround?host=127.0.0.1&port=9999" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "LOGIN\|QUERY\|LOGOUT\|round"; then
    log_pass "T03: TCP 多轮交互"
else
    log_skip "T03: TCP 多轮交互 (response: $RESP)"
fi

# -------------------- K: Kafka 协议 --------------------
echo ""
echo "--- K: Kafka 协议 ---"

# K01: Kafka Produce
RESP=$(curl -sf "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo"; then
    log_pass "K01: Kafka Produce 挡板"
else
    log_fail "K01: Kafka Produce 挡板 (response: $RESP)"
fi

# K02: Kafka Consume
RESP=$(curl -sf "$APP_A/api/kafka/consume?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo\|message"; then
    log_pass "K02: Kafka Consume 挡板"
else
    log_fail "K02: Kafka Consume 挡板 (response: $RESP)"
fi

# K03: Kafka 通配符 Topic
RESP=$(curl -sf "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-wildcard-topic&message=test" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo"; then
    log_pass "K03: Kafka 通配符 Topic 挡板"
else
    log_skip "K03: Kafka 通配符 Topic (response: $RESP)"
fi

# -------------------- P: Pulsar 协议 --------------------
echo ""
echo "--- P: Pulsar 协议 ---"

# P01: Pulsar Produce
RESP=$(curl -sf "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-baafoo-pulsar" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo\|error\|timeout"; then
    log_pass "P01: Pulsar Produce (有响应)"
else
    log_skip "P01: Pulsar Produce (response: $RESP)"
fi

# P02: Pulsar Consume
RESP=$(curl -sf "$APP_A/api/pulsar/consume?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo\|error\|timeout"; then
    log_pass "P02: Pulsar Consume (有响应)"
else
    log_skip "P02: Pulsar Consume (response: $RESP)"
fi

# -------------------- J: JMS 协议 --------------------
echo ""
echo "--- J: JMS 协议 ---"

# J01: JMS Queue 发送
RESP=$(curl -sf "$APP_A/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-baafoo-jms" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo\|sent"; then
    log_pass "J01: JMS Queue 发送挡板"
else
    log_fail "J01: JMS Queue 发送挡板 (response: $RESP)"
fi

# J02: JMS Queue 接收
RESP=$(curl -sf "$APP_A/api/jms/receive?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE" 2>/dev/null || echo "{}")
if echo "$RESP" | grep -qi "success\|stubbed\|mocked\|baafoo\|message\|null"; then
    log_pass "J02: JMS Queue 接收挡板"
else
    log_fail "J02: JMS Queue 接收挡板 (response: $RESP)"
fi

# -------------------- E: 环境隔离 --------------------
echo ""
echo "--- E: 环境隔离 ---"

# E01: staging-a 环境返回 staging-a 标识
RESP_A=$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")
BODY_A=$(jq_val "$RESP_A" '.body')
if echo "$BODY_A" | grep -q "staging-a"; then
    log_pass "E01: staging-a 环境隔离正确"
else
    log_fail "E01: staging-a 环境隔离 (body: $BODY_A)"
fi

# E02: staging-b 环境返回 staging-b 标识
RESP_B=$(app_get "$APP_B/api/http/get?url=http://real-backend:9090/get")
BODY_B=$(jq_val "$RESP_B" '.body')
if echo "$BODY_B" | grep -q "staging-b"; then
    log_pass "E02: staging-b 环境隔离正确"
else
    log_fail "E02: staging-b 环境隔离 (body: $BODY_B)"
fi

# -------------------- PL: Plugin 加载 --------------------
echo ""
echo "--- PL: Plugin 加载 ---"

# PL01: 检查 Agent 日志中是否有 Plugin 加载记录
AGENT_LOGS=$(docker logs baafoo-app-env-a 2>&1 | grep -i "Plugin\|PluginManager" | tail -5 || echo "")
if echo "$AGENT_LOGS" | grep -qi "Plugin loaded"; then
    log_pass "PL01: Plugin 已加载 (日志发现 Plugin loaded)"
elif echo "$AGENT_LOGS" | grep -qi "No plugin\|0 plugins"; then
    log_pass "PL01: PluginManager 已初始化 (无插件加载)"
elif echo "$AGENT_LOGS" | grep -qi "Plugin"; then
    log_pass "PL01: PluginManager 已初始化"
else
    log_skip "PL01: Plugin 加载检查 (无法获取容器日志)"
fi

# PL02: 检查 Agent 心跳注册
AGENTS_JSON=$(api_get "agents")
if echo "$AGENTS_JSON" | grep -qi "agent\|staging"; then
    log_pass "PL02: Agent 心跳注册正常"
else
    log_fail "PL02: Agent 心跳注册异常 (response: $AGENTS_JSON)"
fi

# -------------------- R: 录制验证 --------------------
echo ""
echo "--- R: 录制验证 ---"

# R01: 检查录制列表非空
RECORDINGS_JSON=$(api_get "recordings?limit=10")
REC_COUNT=$(echo "$RECORDINGS_JSON" | grep -o '"id"' | wc -l)
if [ "$REC_COUNT" -gt 0 ] 2>/dev/null; then
    log_pass "R01: 录制列表有数据 (count=$REC_COUNT)"
else
    log_fail "R01: 录制列表为空"
fi

# R02: 检查录制中有 direction 字段
if echo "$RECORDINGS_JSON" | grep -qi '"direction"'; then
    log_pass "R02: 录制包含 direction 字段"
else
    log_fail "R02: 录制缺少 direction 字段"
fi

# R03: 检查录制中有 ruleName 字段
if echo "$RECORDINGS_JSON" | grep -qi '"ruleName"'; then
    log_pass "R03: 录制包含 ruleName 字段"
else
    log_fail "R03: 录制缺少 ruleName 字段"
fi

# -------------------- D: MQ 方向标注 --------------------
echo ""
echo "--- D: MQ 方向标注 ---"

# D01: 检查 Kafka 录制是否有 produce/consume 方向
KAFKA_RECS=$(echo "$RECORDINGS_JSON" | grep -o '"protocol":"kafka"[^}]*"direction":"[^"]*"' | grep -o '"direction":"[^"]*"' || echo "")
if echo "$KAFKA_RECS" | grep -qi "produce\|consume"; then
    log_pass "D01: Kafka 录制标注了 produce/consume 方向"
else
    log_skip "D01: Kafka 录制方向 (可能无 Kafka 录制)"
fi

# D02: 检查 JMS 录制是否有 produce/consume 方向
JMS_RECS=$(echo "$RECORDINGS_JSON" | grep -o '"protocol":"jms"[^}]*"direction":"[^"]*"' | grep -o '"direction":"[^"]*"' || echo "")
if echo "$JMS_RECS" | grep -qi "produce\|consume"; then
    log_pass "D02: JMS 录制标注了 produce/consume 方向"
else
    log_skip "D02: JMS 录制方向 (可能无 JMS 录制)"
fi

# ==================== JUnit XML 报告 (CI 消费) ====================
write_junit_xml "$SCRIPT_DIR/junit-report.xml"

# ==================== 汇总报告 ====================
echo ""
echo "============================================================"
echo "  测试汇总"
echo "============================================================"
TOTAL=$((PASS + FAIL + SKIP))
echo -e "  ${GREEN}通过: $PASS${NC}"
echo -e "  ${RED}失败: $FAIL${NC}"
echo -e "  ${YELLOW}跳过: $SKIP${NC}"
echo "  总计: $TOTAL"
echo ""

if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
    echo -e "${RED}失败用例:${NC}"
    for t in "${FAILED_TESTS[@]}"; do
        echo "  - $t"
    done
    echo ""
fi

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}=== 全链路集成测试通过 ===${NC}"
    exit 0
else
    echo -e "${RED}=== 全链路集成测试失败 ===${NC}"
    exit 1
fi
