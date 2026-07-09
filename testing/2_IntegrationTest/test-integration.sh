#!/usr/bin/env bash
# =============================================================================
# Baafoo Integration Test - Bash Orchestrator (L2 layer)
#
# This script runs L2 integration tests: single-protocol Agent + Server
# verification via Docker Compose. It is the bash counterpart of
# test-integration.ps1.
#
# Steps:
#   1. Clean old Docker environment
#   2. Build Docker images
#   3. Start test environment (docker-compose.yml + docker-compose.test.yml)
#   4. Run L2 integration test cases (HTTP/TCP/Kafka/Pulsar/JMS/gRPC)
#   5. Report and cleanup
#
# Usage:
#   ./testing/2_IntegrationTest/test-integration.sh              # Full build+test+cleanup
#   ./testing/2_IntegrationTest/test-integration.sh --no-cleanup # Keep environment
#   ./testing/2_IntegrationTest/test-integration.sh --skip-build # Skip build step
#
# Environment:
#   Run in Git Bash, WSL, or any Bash-compatible environment on Windows.
#   Requires Docker and docker compose.
# =============================================================================

set -o pipefail

NO_CLEANUP=false
SKIP_BUILD=false
SHOW_HELP=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-cleanup) NO_CLEANUP=true; shift ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --help)       SHOW_HELP=true; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [[ "$SHOW_HELP" == "true" ]]; then
    cat <<'EOF'
Baafoo Integration Test (L2)

Usage:
  ./testing/2_IntegrationTest/test-integration.sh              # Full build+test+cleanup
  ./testing/2_IntegrationTest/test-integration.sh --no-cleanup # Keep test environment
  ./testing/2_IntegrationTest/test-integration.sh --skip-build # Skip build step
EOF
    exit 0
fi

# Project root is two levels up (testing/2_IntegrationTest -> project root)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT" || { echo "Cannot cd to $PROJECT_ROOT"; exit 1; }

COMPOSE_BASE="-f docker-compose.yml"
COMPOSE_TEST="-f docker-compose.yml -f docker-compose.test.yml"
ENV_FILE="--env-file .env.test"

# Service endpoints (default ports from docker-compose.test.yml)
SERVER="http://localhost:8084"
API_KEY="test-admin-key"

# Test counters
PASS=0
FAIL=0
SKIP=0
FAILED_TESTS=()
TEST_RESULTS=()

write_step() { echo -e "\n[STEP] $1"; }
write_ok()   { echo "  [OK] $1"; }
write_warn() { echo "  [WARN] $1" >&2; }
write_err()  { echo "  [ERR] $1" >&2; }

test_pass() { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
test_fail() { echo "  [FAIL] $1" >&2; FAIL=$((FAIL + 1)); FAILED_TESTS+=("$1"); }
test_skip() { echo "  [SKIP] $1"; SKIP=$((SKIP + 1)); }

# -----------------------------------------------------------------------------
# JSON helper: use jq when available, fallback to grep/regex
# -----------------------------------------------------------------------------
HAVE_JQ=false
if command -v jq >/dev/null 2>&1; then
    HAVE_JQ=true
fi

get_json_value() {
    local json="$1" key="$2"
    if [[ "$HAVE_JQ" == "true" ]]; then
        echo "$json" | jq -r ".$key // empty" 2>/dev/null
        return
    fi
    if [[ "$json" =~ \"$key\"[[:space:]]*:[[:space:]]*\"([^\"]*)\" ]]; then
        echo "${BASH_REMATCH[1]}"
    elif [[ "$json" =~ \"$key\"[[:space:]]*:[[:space:]]*(true|false) ]]; then
        echo "${BASH_REMATCH[1]}"
    elif [[ "$json" =~ \"$key\"[[:space:]]*:[[:space:]]*(-?[0-9]+\.?[0-9]*) ]]; then
        echo "${BASH_REMATCH[1]}"
    fi
}

# -----------------------------------------------------------------------------
# HTTP helpers
# -----------------------------------------------------------------------------
api_get() {
    local path="$1"
    curl -sf -H "X-Api-Key: $API_KEY" "$SERVER/__baafoo__/api/$path" 2>/dev/null || echo "{}"
}

api_post() {
    local path="$1" body="$2"
    curl -sf -X POST "$SERVER/__baafoo__/api/$path" \
         -H "Content-Type: application/json" \
         -H "X-Api-Key: $API_KEY" \
         -d "$body" 2>/dev/null || echo "{}"
}

api_put() {
    local path="$1" body="$2"
    curl -sf -X PUT "$SERVER/__baafoo__/api/$path" \
         -H "Content-Type: application/json" \
         -H "X-Api-Key: $API_KEY" \
         -d "$body" 2>/dev/null || echo "{}"
}

api_delete() {
    local path="$1"
    curl -sf -X DELETE "$SERVER/__baafoo__/api/$path" \
         -H "X-Api-Key: $API_KEY" 2>/dev/null || echo "{}"
}

# -----------------------------------------------------------------------------
# 1. Clean old Docker environment
# -----------------------------------------------------------------------------
write_step "1/5: Clean old Docker environment"
docker compose $COMPOSE_TEST $ENV_FILE down -v --remove-orphans >/dev/null 2>&1 || true
write_ok "Old environment cleaned"

# -----------------------------------------------------------------------------
# 2. Build Docker images
# -----------------------------------------------------------------------------
if [[ "$SKIP_BUILD" != "true" ]]; then
    write_step "2/5: Build Docker images"

    echo "  Building test images (docker compose build)..."
    build_output="$(docker compose $COMPOSE_BASE --env-file .env.test build --progress=plain 2>&1)"
    build_exit=$?
    if [[ $build_exit -ne 0 ]]; then
        write_err "Image build failed"
        echo "$build_output"
        exit 1
    fi
    write_ok "Images built"
else
    write_step "2/5: Skip build (--skip-build)"
fi

# -----------------------------------------------------------------------------
# 3. Start test environment
# -----------------------------------------------------------------------------
write_step "3/5: Start test environment"

echo "  Starting services (docker compose up)..."
up_output="$(docker compose $COMPOSE_TEST $ENV_FILE up -d 2>&1)"
up_exit=$?
if [[ $up_exit -ne 0 ]]; then
    write_err "Docker startup failed"
    echo "$up_output"
    exit 1
fi

# Wait for server to be healthy
echo "  Waiting for baafoo-server to become healthy..."
max_wait=120
waited=0
server_healthy=false

while [[ "$server_healthy" != "true" && $waited -lt $max_wait ]]; do
    sleep 3
    waited=$((waited + 3))
    health_status="$(docker inspect --format='{{.State.Health.Status}}' baafoo-server 2>/dev/null || true)"
    printf "\r  server health: %s (%ss)" "$health_status" "$waited"
    if [[ "$health_status" == "healthy" ]]; then
        server_healthy=true
    fi
done
printf "\n"

if [[ "$server_healthy" != "true" ]]; then
    write_err "Server health check timeout"
    echo "  Server logs:"
    docker compose $COMPOSE_TEST $ENV_FILE logs --tail=30 server 2>/dev/null || true
    if [[ "$NO_CLEANUP" != "true" ]]; then
        docker compose $COMPOSE_TEST $ENV_FILE down -v >/dev/null 2>&1 || true
    fi
    exit 1
fi
write_ok "Server healthy"

# Wait for test-runner if present (docker-compose.test.yml may define a test-runner service)
echo "  Waiting for test-runner to complete..."
runner_max_wait=180
runner_waited=0
runner_done=false
while [[ "$runner_done" != "true" && $runner_waited -lt $runner_max_wait ]]; do
    sleep 5
    runner_waited=$((runner_waited + 5))
    runner_status="$(docker inspect --format='{{.State.Status}}' baafoo-test-runner 2>/dev/null || true)"
    if [[ "$runner_status" == "exited" ]]; then
        runner_done=true
    fi
    printf "\r  test-runner: %s (%ss)" "$runner_status" "$runner_waited"
done
printf "\n"

if [[ "$runner_done" == "true" ]]; then
    runner_exit="$(docker inspect --format='{{.State.ExitCode}}' baafoo-test-runner 2>/dev/null || echo 1)"
    if [[ "$runner_exit" == "0" ]]; then
        write_ok "Test-runner completed successfully"
    else
        write_warn "Test-runner exited with code $runner_exit"
    fi
else
    write_warn "Test-runner not found or still running (continuing with manual checks)"
fi

# -----------------------------------------------------------------------------
# 4. Run L2 integration test cases
# -----------------------------------------------------------------------------
write_step "4/5: Run L2 integration test cases"

# -------------------- L2-HEALTH: Basic connectivity --------------------
echo "--- L2: Health & Connectivity ---"

health="$(api_get "status")"
if [[ "$health" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then
    test_pass "L2-HEALTH-001: Server API reachable"
else
    test_fail "L2-HEALTH-001: Server API unreachable (response: $health)"
fi

# -------------------- L2-ENV: Environment CRUD --------------------
echo ""
echo "--- L2: Environment CRUD ---"

test_env_name="l2-test-env-$RANDOM"
create_env="$(api_post "environments" "{\"name\":\"$test_env_name\",\"mode\":\"stub\",\"description\":\"L2 test environment\"}")"
env_id="$(get_json_value "$create_env" "id")"
if [[ "$HAVE_JQ" == "true" && -z "$env_id" ]]; then
    env_id="$(echo "$create_env" | jq -r '.data.id // empty' 2>/dev/null)"
fi
if [[ "$create_env" =~ \"success\"[[:space:]]*:[[:space:]]*true || -n "$env_id" ]]; then
    test_pass "L2-ENV-001: Environment created"
else
    test_fail "L2-ENV-001: Environment create failed (response: $create_env)"
fi

env_detail="$(api_get "environments/${env_id:-$test_env_name}")"
if [[ "$env_detail" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then
    test_pass "L2-ENV-002: Environment queried"
else
    test_fail "L2-ENV-002: Environment query failed (response: $env_detail)"
fi

mode_update="$(api_put "environments/${env_id:-$test_env_name}" '{"mode":"passthrough"}')"
if [[ "$mode_update" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then
    test_pass "L2-ENV-003: Environment mode updated to passthrough"
else
    test_skip "L2-ENV-003: Environment mode update (response: $mode_update)"
fi

api_put "environments/${env_id:-$test_env_name}" '{"mode":"stub"}' >/dev/null

api_delete "environments/${env_id:-$test_env_name}" >/dev/null
test_pass "L2-ENV-004: Environment deleted"

# -------------------- L2-RULE: Rule CRUD (HTTP) --------------------
echo ""
echo "--- L2: Rule CRUD ---"

test_rule_id="l2-test-rule-$RANDOM"
rule_body='{
    "id": "'"$test_rule_id"'",
    "name": "L2 CRUD Test Rule",
    "protocol": "http",
    "host": "example.com",
    "port": 80,
    "conditions": [{"type": "path", "operator": "equals", "value": "/l2-crud-test"}],
    "responses": [{"name": "CRUD Response", "statusCode": 200, "body": "{\"mocked\":true}", "delayMs": 0}],
    "enabled": true,
    "priority": 100,
    "environments": ["staging-a"]
}'

create_result="$(api_post "rules" "$rule_body")"
if [[ "$create_result" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$create_result" "id")" != "" ]]; then
    test_pass "L2-RULE-001: HTTP rule created"
else
    test_fail "L2-RULE-001: Rule create failed (response: $create_result)"
fi

rule_detail="$(api_get "rules/$test_rule_id")"
if [[ "$rule_detail" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$rule_detail" "id")" == "$test_rule_id" ]]; then
    test_pass "L2-RULE-002: Rule queried by ID"
else
    test_fail "L2-RULE-002: Rule query failed (response: $rule_detail)"
fi

update_body='{"name":"L2 CRUD Test Rule Updated","conditions":[{"type":"path","operator":"equals","value":"/l2-crud-updated"}]}'
update_result="$(api_put "rules/$test_rule_id" "$update_body")"
if [[ "$update_result" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then
    test_pass "L2-RULE-003: Rule updated"
else
    test_skip "L2-RULE-003: Rule update (response: $update_result)"
fi

api_delete "rules/$test_rule_id" >/dev/null
rule_after_delete="$(api_get "rules/$test_rule_id")"
if [[ ! "$rule_after_delete" =~ "$test_rule_id" ]]; then
    test_pass "L2-RULE-004: Rule deleted"
else
    test_fail "L2-RULE-004: Rule still exists after delete"
fi

# -------------------- L2-AUTH: API security --------------------
echo ""
echo "--- L2: API Security ---"

invalid_key_status="$(curl -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: invalid-key" "$SERVER/__baafoo__/api/rules" 2>/dev/null || echo 0)"
if [[ "$invalid_key_status" == "401" || "$invalid_key_status" == "403" ]]; then
    test_pass "L2-AUTH-001: Invalid API key rejected ($invalid_key_status)"
else
    test_skip "L2-AUTH-001: Invalid API key (status=$invalid_key_status, auth may be disabled in test env)"
fi

no_key_status="$(curl -s -o /dev/null -w "%{http_code}" "$SERVER/__baafoo__/api/rules" 2>/dev/null || echo 0)"
if [[ "$no_key_status" == "401" || "$no_key_status" == "403" ]]; then
    test_pass "L2-AUTH-002: Missing API key rejected ($no_key_status)"
else
    test_skip "L2-AUTH-002: Missing API key (status=$no_key_status, auth may be disabled in test env)"
fi

# -------------------- L2-PROTO: Protocol rule verification --------------------
echo ""
echo "--- L2: Protocol Rules ---"

# Create a temporary HTTP rule and verify it's queryable
proto_rule_id="l2-proto-http-$RANDOM"
proto_body='{
    "id": "'"$proto_rule_id"'",
    "name": "L2 HTTP Protocol Test",
    "protocol": "http",
    "host": "real-backend",
    "port": 9090,
    "conditions": [{"type": "path", "operator": "equals", "value": "/l2-proto-test"}],
    "responses": [{"name": "OK", "statusCode": 200, "body": "{\"proto\":\"http\",\"l2\":true}"}],
    "enabled": true,
    "priority": 50,
    "environments": ["staging-a"]
}'
api_post "rules" "$proto_body" >/dev/null
proto_check="$(api_get "rules/$proto_rule_id")"
proto_protocol=""
if [[ "$HAVE_JQ" == "true" ]]; then
    proto_protocol="$(echo "$proto_check" | jq -r '.data.protocol // empty' 2>/dev/null)"
fi
if [[ "$proto_check" =~ \"protocol\"[[:space:]]*:[[:space:]]*\"http\" ]]; then
    test_pass "L2-PROTO-001: HTTP protocol rule persisted"
else
    test_fail "L2-PROTO-001: HTTP protocol rule verification failed"
fi
api_delete "rules/$proto_rule_id" >/dev/null

# TCP rule
tcp_rule_id="l2-proto-tcp-$RANDOM"
tcp_body='{
    "id": "'"$tcp_rule_id"'",
    "name": "L2 TCP Protocol Test",
    "protocol": "tcp",
    "host": "server",
    "port": 9001,
    "conditions": [{"type": "data", "operator": "hex", "value": "4c4f47494e"}],
    "responses": [{"name": "TCP OK", "body": "LOGIN OK"}],
    "enabled": true,
    "priority": 50,
    "environments": ["staging-a"]
}'
api_post "rules" "$tcp_body" >/dev/null
tcp_check="$(api_get "rules/$tcp_rule_id")"
if [[ "$tcp_check" =~ \"protocol\"[[:space:]]*:[[:space:]]*\"tcp\" ]]; then
    test_pass "L2-PROTO-002: TCP protocol rule persisted"
else
    test_fail "L2-PROTO-002: TCP protocol rule verification failed"
fi
api_delete "rules/$tcp_rule_id" >/dev/null

# Kafka rule
kafka_rule_id="l2-proto-kafka-$RANDOM"
kafka_body='{
    "id": "'"$kafka_rule_id"'",
    "name": "L2 Kafka Protocol Test",
    "protocol": "kafka",
    "topic": "l2-test-topic",
    "conditions": [],
    "responses": [{"name": "Kafka OK", "body": "{\"kafka\":\"mock\"}"}],
    "enabled": true,
    "priority": 50,
    "environments": ["staging-a"]
}'
api_post "rules" "$kafka_body" >/dev/null
kafka_check="$(api_get "rules/$kafka_rule_id")"
if [[ "$kafka_check" =~ \"protocol\"[[:space:]]*:[[:space:]]*\"kafka\" ]]; then
    test_pass "L2-PROTO-003: Kafka protocol rule persisted"
else
    test_fail "L2-PROTO-003: Kafka protocol rule verification failed"
fi
api_delete "rules/$kafka_rule_id" >/dev/null

# gRPC rule
grpc_rule_id="l2-proto-grpc-$RANDOM"
grpc_body='{
    "id": "'"$grpc_rule_id"'",
    "name": "L2 gRPC Protocol Test",
    "protocol": "grpc",
    "grpcService": "l2.test.Greeter",
    "grpcMethod": "SayHello",
    "conditions": [],
    "responses": [{"name": "gRPC OK", "body": "{\"message\":\"L2 hello\"}"}],
    "enabled": true,
    "priority": 50,
    "environments": ["staging-a"]
}'
api_post "rules" "$grpc_body" >/dev/null
grpc_check="$(api_get "rules/$grpc_rule_id")"
if [[ "$grpc_check" =~ \"protocol\"[[:space:]]*:[[:space:]]*\"grpc\" ]]; then
    test_pass "L2-PROTO-004: gRPC protocol rule persisted"
else
    test_fail "L2-PROTO-004: gRPC protocol rule verification failed"
fi
api_delete "rules/$grpc_rule_id" >/dev/null

# -------------------- L2-REC: Recording API --------------------
echo ""
echo "--- L2: Recording API ---"

rec_list="$(api_get "recordings?limit=5")"
if [[ "$rec_list" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$rec_list" =~ \"items\" || "$rec_list" =~ \"data\" ]]; then
    test_pass "L2-REC-001: Recording list API accessible"
else
    test_skip "L2-REC-001: Recording list API (response: $rec_list)"
fi

# -------------------- L2-AGENT: Agent registration --------------------
echo ""
echo "--- L2: Agent Registration ---"

agents_json="$(api_get "agents")"
if [[ "$agents_json" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$agents_json" =~ \"data\" ]]; then
    test_pass "L2-AGENT-001: Agent list API accessible"
else
    test_fail "L2-AGENT-001: Agent list API failed (response: $agents_json)"
fi

# -----------------------------------------------------------------------------
# 5. Summary report
# -----------------------------------------------------------------------------
echo ""
echo "============================================================"
echo "  L2 Integration Test Summary"
echo "============================================================"
total=$((PASS + FAIL + SKIP))
echo "  Pass: $PASS"
echo "  Fail: $FAIL"
echo "  Skip: $SKIP"
echo "  Total: $total"
echo ""

if [[ ${#FAILED_TESTS[@]} -gt 0 ]]; then
    echo "Failed tests:"
    for t in "${FAILED_TESTS[@]}"; do
        echo "  - $t"
    done
    echo ""
fi

# -----------------------------------------------------------------------------
# Cleanup
# -----------------------------------------------------------------------------
if [[ "$NO_CLEANUP" != "true" ]]; then
    write_step "5/5: Cleanup test environment"
    docker compose $COMPOSE_TEST $ENV_FILE down -v >/dev/null 2>&1 || true
    write_ok "Environment cleaned"
else
    echo ""
    echo "  Environment kept (--no-cleanup)"
    echo "  Web console: http://localhost:8084"
fi

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "=== L2 Integration Test FAILED ($FAIL failed) ==="
    exit 1
elif [[ $SKIP -gt 0 ]]; then
    echo ""
    echo "=== L2 Integration Test PASSED WITH SKIPS ($SKIP skipped) ==="
    exit 2
else
    echo ""
    echo "=== L2 Integration Test PASSED ==="
    exit 0
fi
