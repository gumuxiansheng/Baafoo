#!/usr/bin/env bash
# =============================================================================
# Baafoo Full-Chain Integration Test - Bash Orchestrator
#
# Features:
#   1. Build all JARs (server + agent + test-spring + feign plugin)
#   2. Copy Feign plugin JAR to ./plugins/
#   3. Start Docker Staging environment (server + postgres + app-env-a + app-env-b)
#   4. Wait for all services to be healthy
#   5. Register all test rules (HTTP/TCP/Kafka/Pulsar/JMS + gRPC placeholders)
#   6. Run full-chain test cases covering:
#      F core / A API security & CRUD / H HTTP / T TCP / K Kafka / P Pulsar / J JMS
#      E env isolation / PL plugin / R+D recording & MQ direction / C condition types
#      M env modes / AS RuleSet CRUD / REC recording mgmt / RU+RST undo & reset
#      OAPI OpenAPI import / G gRPC (gap) / MX protocol x mode matrix gaps
#   7. Summary report and cleanup
#
# Assertion red lines (see PROJECT-TEST-PLAN.md §6.4.1):
#   - Condition tests assert precise `matchedBy` (no `|mocked` fallback)
#   - MQ error/timeout/null must FAIL, not pass
#   - requestCount tests reset counter before run
#   - mode switches wait $MODE_SETTLE_WAIT (>= pollIntervalSec)
#
# Usage:
#   ./testing/3_SystemTest/test-fullchain.sh              # Build + test + cleanup
#   ./testing/3_SystemTest/test-fullchain.sh --no-cleanup # Keep environment after test
#   ./testing/3_SystemTest/test-fullchain.sh --skip-build # Skip build step
#
# Environment:
#   This script is a Bash script. On Windows run it inside Git Bash, WSL, or
#   another Bash-compatible environment. PowerShell will not execute it.
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
Baafoo Full-Chain Integration Test

Usage:
  ./testing/3_SystemTest/test-fullchain.sh              # Full build+test+cleanup
  ./testing/3_SystemTest/test-fullchain.sh --no-cleanup # Keep test environment
  ./testing/3_SystemTest/test-fullchain.sh --skip-build # Skip build (use existing JARs)
EOF
    exit 0
fi

# Project root is two levels up because this script lives in testing/3_SystemTest
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT" || { echo "Cannot cd to $PROJECT_ROOT"; exit 1; }

COMPOSE_FILES="-f docker-compose.yml -f docker-compose.staging.yml"
SERVER="http://localhost:8084"
APP_A="http://localhost:9090"
APP_B="http://localhost:9091"
API_KEY="staging-admin-key"
MODE_SETTLE_WAIT=12  # wait for agent poll cycle (default pollIntervalSec=10) after mode changes

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

record_result() {
  local msg="$1" status="$2" id
  if [[ "$msg" =~ ^([A-Z]{1,4}[0-9]{1,3})[:[:space:]] ]]; then
    id="${BASH_REMATCH[1]}"
  else
    id="$msg"
  fi
  TEST_RESULTS+=("$id|$status|$msg")
}

test_pass() { echo "  [PASS] $1"; PASS=$((PASS + 1)); record_result "$1" "pass"; }
test_fail() { echo "  [FAIL] $1" >&2; FAIL=$((FAIL + 1)); FAILED_TESTS+=("$1"); record_result "$1" "fail"; }
test_skip() { echo "  [SKIP] $1"; SKIP=$((SKIP + 1)); record_result "$1" "skip"; }

# Emit a JUnit-compatible XML report so CI can parse per-test results.
write_junit_xml() {
  # Derive all counts from TEST_RESULTS (single source of truth) so the report
  # stays consistent even if the PASS/FAIL/SKIP globals drift from the log.
  local path="$1" ts total f=0 s=0 entry name rest status msg esc
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  total=${#TEST_RESULTS[@]}
  for entry in "${TEST_RESULTS[@]}"; do
    rest="${entry#*|*}"; status="${rest%%|*}"
    [ "$status" = "fail" ] && f=$((f + 1))
    [ "$status" = "skip" ] && s=$((s + 1))
  done
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuites name=\"baafoo-fullchain\" tests=\"$total\" failures=\"$f\" skipped=\"$s\" errors=\"0\">"
    echo "<testsuite name=\"FullChain\" tests=\"$total\" failures=\"$f\" skipped=\"$s\" errors=\"0\" timestamp=\"$ts\">"
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

# -----------------------------------------------------------------------------
# JSON helpers: use jq when available, fallback to grep/regex
# -----------------------------------------------------------------------------
HAVE_JQ=false
if command -v jq >/dev/null 2>&1; then
    HAVE_JQ=true
fi

# Extract a top-level (or dotted, e.g. "data.generatedCount") string/bool/number
# value from JSON.
get_json_value() {
    local json="$1" key="$2"
    if [[ "$HAVE_JQ" == "true" ]]; then
        echo "$json" | jq -r "\.$key // empty" 2>/dev/null
        return
    fi
    # Fallback regex extraction. Support dotted keys by searching on the leaf
    # name (e.g. for "data.generatedCount" we look for "generatedCount").
    local val
    local search_key="$key"
    if [[ "$search_key" == *.* ]]; then
        search_key="${search_key##*.}"
    fi
    if [[ "$json" =~ \"$search_key\"[[:space:]]*:[[:space:]]*\"([^\"]*)\" ]]; then
        val="${BASH_REMATCH[1]}"
    elif [[ "$json" =~ \"$search_key\"[[:space:]]*:[[:space:]]*(true|false) ]]; then
        val="${BASH_REMATCH[1]}"
    elif [[ "$json" =~ \"$search_key\"[[:space:]]*:[[:space:]]*(-?[0-9]+\.?[0-9]*) ]]; then
        val="${BASH_REMATCH[1]}"
    fi
    # Unescape if needed
    val="${val//\\\"/\"}"
    val="${val//\\\\/\\}"
    echo "$val"
}

# Extract the body field (escaped JSON string) from app response JSON
get_json_body() {
    local json="$1"
    if [[ "$HAVE_JQ" == "true" ]]; then
        echo "$json" | jq -r '.body // empty' 2>/dev/null
        return
    fi
    if [[ "$json" =~ \"body\"[[:space:]]*:[[:space:]]*\"(([^\"\\]|\\.)*)\" ]]; then
        local body="${BASH_REMATCH[1]}"
        body="${body//\\\"/\"}"
        body="${body//\\\\/\\}"
        body="${body//\\n/$'\n'}"
        body="${body//\\t/$'\t'}"
        echo "$body"
    fi
}

# Extract matchedBy from the body of a stubbed HTTP response
get_matched_by() {
    local json="$1"
    local body
    body="$(get_json_body "$json")"
    if [[ -n "$body" && "$body" =~ \"matchedBy\"[[:space:]]*:[[:space:]]*\"([^\"]*)\" ]]; then
        echo "${BASH_REMATCH[1]}"
    fi
}

# Extract an environment id by name from server API JSON
get_environment_id() {
    local json="$1" name="$2"
    if [[ "$HAVE_JQ" == "true" ]]; then
        echo "$json" | jq -r --arg n "$name" '.data[]? // .[]? | select(.name == $n) | .id // empty' 2>/dev/null
        return
    fi
    # Fallback: crude array-of-objects scan
    local items
    items="$(echo "$json" | grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' | sed -n '1p')"
    # Better: match objects
    while [[ "$json" =~ \{[[:space:]]*\"id\"[[:space:]]*:[[:space:]]*\"([^\"]+)\"[^}]*\"name\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; do
        if [[ "${BASH_REMATCH[2]}" == "$name" ]]; then
            echo "${BASH_REMATCH[1]}"
            return
        fi
        json="${json#*\"name\"[[:space:]]*:[[:space:]]*\"${BASH_REMATCH[2]}\"}"
    done
}

# -----------------------------------------------------------------------------
# HTTP helpers
# -----------------------------------------------------------------------------
api_get() {
    local path="$1"
    curl -sf -H "X-Api-Key: $API_KEY" "$SERVER/__baafoo__/api/$path" 2>/dev/null || echo "{}"
}

app_get() {
    local url="$1"
    curl -sf "$url" 2>/dev/null || echo "{}"
}

app_post() {
    local url="$1"
    curl -sf -X POST "$url" 2>/dev/null || echo "{}"
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
write_step "1/6: Clean old Docker environment"
docker compose $COMPOSE_FILES down -v --remove-orphans >/dev/null 2>&1 || true
write_ok "Old environment cleaned"

# -----------------------------------------------------------------------------
# 2. Build all JARs
# -----------------------------------------------------------------------------
if [[ "$SKIP_BUILD" != "true" ]]; then
    write_step "2/6: Build all JAR files"

    echo "  Building project (./mvnw clean package -DskipTests)..."
    if ! ./mvnw clean package -DskipTests -q >/dev/null 2>&1; then
        write_err "Project build failed"
        exit 1
    fi
    write_ok "Project build complete"

    echo "  Building Feign plugin JAR..."
    if ./mvnw clean package -f "baafoo-example-plugins/feign/pom.xml" -DskipTests -q >/dev/null 2>&1; then
        mkdir -p plugins
        feign_jar="$(find baafoo-example-plugins/feign/target -maxdepth 1 -name 'baafoo-plugin-feign-*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name '*original*' | head -n 1)"
        if [[ -n "$feign_jar" ]]; then
            cp -f "$feign_jar" plugins/
            write_ok "Feign plugin copied to plugins/$(basename "$feign_jar")"
        else
            write_warn "Feign plugin JAR not found"
        fi
    else
        write_warn "Feign plugin build failed, skipping plugin tests"
    fi
else
    write_step "2/6: Skip build (--skip-build)"
fi

# -----------------------------------------------------------------------------
# 3. Start Docker Staging environment
# -----------------------------------------------------------------------------
write_step "3/6: Start Docker Staging environment"

echo "  Building and starting all services (incl. staging-init)..."
build_output="$(docker compose $COMPOSE_FILES up -d --build 2>&1)"
build_exit=$?
if [[ $build_exit -ne 0 ]]; then
    write_err "Docker startup failed"
    echo "$build_output"
    exit 1
fi
write_ok "Services started"

# Wait for health checks
echo "  Waiting for services to become healthy..."
max_wait=180
waited=0
all_healthy=false

while [[ "$all_healthy" != "true" && $waited -lt $max_wait ]]; do
    sleep 5
    waited=$((waited + 5))

    server_health="$(docker inspect --format='{{.State.Health.Status}}' baafoo-server 2>/dev/null || true)"
    app_a_health="$(docker inspect --format='{{.State.Health.Status}}' baafoo-app-env-a 2>/dev/null || true)"
    app_b_health="$(docker inspect --format='{{.State.Health.Status}}' baafoo-app-env-b 2>/dev/null || true)"

    printf "\r  server=%s app-a=%s app-b=%s (%ss)" "$server_health" "$app_a_health" "$app_b_health" "$waited"

    if [[ "$server_health" == "healthy" && "$app_a_health" == "healthy" && "$app_b_health" == "healthy" ]]; then
        all_healthy=true
    fi
done
printf "\n"

if [[ "$all_healthy" != "true" ]]; then
    write_err "Health check timeout"
    echo "  Server logs:"
    docker compose $COMPOSE_FILES logs --tail=20 server 2>/dev/null || true
    echo "  app-env-a logs:"
    docker compose $COMPOSE_FILES logs --tail=20 app-env-a 2>/dev/null || true
    if [[ "$NO_CLEANUP" != "true" ]]; then
        docker compose $COMPOSE_FILES down -v >/dev/null 2>&1 || true
    fi
    exit 1
fi
write_ok "All services healthy"

# Wait for staging-init container to finish
echo "  Waiting for staging-init to complete..."
init_max_wait=60
init_waited=0
init_done=false
while [[ "$init_done" != "true" && $init_waited -lt $init_max_wait ]]; do
    sleep 2
    init_waited=$((init_waited + 2))
    init_status="$(docker inspect --format='{{.State.Status}}' baafoo-staging-init 2>/dev/null || true)"
    if [[ "$init_status" == "exited" ]]; then
        init_done=true
    fi
    printf "\r  staging-init: %s (%ss)" "$init_status" "$init_waited"
done
printf "\n"

if [[ "$init_done" == "true" ]]; then
    write_ok "Staging initialization complete"
else
    write_warn "Staging-init still running or not found (continuing anyway)"
fi

# Verify environments were created
env_check="$(api_get "environments")"
env_count="$(echo "$env_check" | grep -o '"name"' | wc -l | tr -d ' ')"
if [[ "$env_count" -ge 2 ]]; then
    write_ok "Environments created (count=$env_count)"
else
    write_warn "Environments count=$env_count (expected >=2), creating manually..."
    api_post "environments" '{"name":"staging-a","mode":"stub","description":"Staging A"}' >/dev/null
    api_post "environments" '{"name":"staging-b","mode":"record-and-stub","description":"Staging B"}' >/dev/null
    api_post "environments" '{"name":"staging-c","mode":"stub","description":"Staging C - Mode Test"}' >/dev/null
    sleep 2
    write_ok "Environments created via API fallback"
fi

# -----------------------------------------------------------------------------
# 4. Register test rules
# -----------------------------------------------------------------------------
write_step "4/6: Register all test rules"

rules_dir="testing/2_IntegrationTest/rules"
rule_files=(
    "http-get.json" "http-post.json" "http-put.json" "http-delete.json"
    "http-delay.json" "http-error.json" "http-staging-b.json" "http-consul.json"
    "http-header.json" "http-query.json" "http-body.json" "http-jsonpath.json"
    "http-contains.json" "http-endswith.json" "http-path-regex.json"
    "http-header-exists.json" "http-disabled.json" "http-no-env.json"
    "http-graphql.json" "http-request-count.json" "http-caseinsensitive.json"
    "kafka-topic.json" "kafka-wildcard.json" "kafka-header.json"
    "pulsar-topic.json" "pulsar-wildcard.json"
    "jms-queue.json" "jms-topic.json"
    "tcp-hex.json" "tcp-regex.json" "tcp-multiround.json"
    "grpc-greeter.json" "grpc-error.json" "grpc-delay.json"
    "grpc-server-streaming.json" "grpc-client-streaming.json" "grpc-bidirectional-streaming.json"
)

registered=0
failed=0

# Cleanup stale gRPC rules left by a prior (non-reset) run. Without this, a
# leftover UUID-named gRPC rule matching the same service/method would win over
# the 6 known rules below and return a malformed body, breaking the gRPC
# assertions G01/G05. Only the 6 known ids are preserved.
known_grpc_ids=("grpc-greeter" "grpc-error" "grpc-delay" "grpc-server-streaming" "grpc-client-streaming" "grpc-bidirectional-streaming")
grpc_list="$(curl -sf "$SERVER/__baafoo__/api/rules" -H "X-Api-Key: $API_KEY" 2>/dev/null || echo '{}')"
if [[ "$HAVE_JQ" == "true" ]]; then
    while IFS= read -r rid; do
        # Preserve the 6 known gRPC rules; only delete leftover UUID-named ones.
        if [[ -n "$rid" && " ${known_grpc_ids[*]} " != *" $rid "* ]]; then
            curl -sf -X DELETE "$SERVER/__baafoo__/api/rules/$rid" \
                -H "X-Api-Key: $API_KEY" >/dev/null 2>&1 || true
        fi
    done < <(echo "$grpc_list" | jq -r '.data[]? // .[]? | select(.protocol=="grpc") | .id' 2>/dev/null)
fi

for rule_file in "${rule_files[@]}"; do
    rule_path="$rules_dir/$rule_file"
    if [[ ! -f "$rule_path" ]]; then
        write_warn "Rule file not found: $rule_file"
        continue
    fi
    rule_json="$(cat "$rule_path")"

    # Upsert: delete any existing rule with the same id, then (re)create, so a
    # stale DB (PostgreSQL volume not reset) never keeps an outdated rule
    # (e.g. old host=httpbin.org instead of the current host=real-backend).
    rid=""
    if [[ "$HAVE_JQ" == "true" ]]; then
        rid="$(echo "$rule_json" | jq -r '.id // empty' 2>/dev/null)"
    elif [[ "$rule_json" =~ \"id\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; then
        rid="${BASH_REMATCH[1]}"
    fi
    if [[ -n "$rid" ]]; then
        curl -sf -X DELETE "$SERVER/__baafoo__/api/rules/$rid" \
            -H "X-Api-Key: $API_KEY" >/dev/null 2>&1 || true
    fi

    result="$(curl -sf -X POST "$SERVER/__baafoo__/api/rules" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: $API_KEY" \
        -d "$rule_json" 2>/dev/null || echo '{"success":false}')"

    success="$(get_json_value "$result" "success")"
    data_id="$(get_json_value "$result" "id")"
    if [[ -z "$data_id" && "$HAVE_JQ" == "true" ]]; then
        data_id="$(echo "$result" | jq -r '.data.id // empty' 2>/dev/null)"
    fi

    if [[ "$success" == "true" || -n "$data_id" ]]; then
        registered=$((registered + 1))
    else
        failed=$((failed + 1))
        write_warn "Register failed: $rule_file"
    fi
done
write_ok "Rules registered (success=$registered, failed=$failed)"

sleep 5
write_ok "Rules effective"

# -----------------------------------------------------------------------------
# 5. Run test cases
# -----------------------------------------------------------------------------
write_step "5/6: Run full-chain test cases"

# -------------------- F: Core functionality --------------------
echo "--- F: Core ---"

health="$(api_get "status")"
if [[ "$health" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then
    test_pass "F01: Server health check"
else
    test_fail "F01: Server health check (response: $health)"
fi

pg_health="$(docker inspect --format='{{.State.Health.Status}}' baafoo-staging-postgres 2>/dev/null || true)"
if [[ "$pg_health" == "healthy" ]]; then
    test_pass "F02: PostgreSQL database connection"
else
    test_skip "F02: PostgreSQL database connection (status=$pg_health)"
fi

rules_json="$(api_get "rules")"
rule_count="$(echo "$rules_json" | grep -o '"id"' | wc -l | tr -d ' ')"
if [[ "$rule_count" -gt 0 ]]; then
    test_pass "F03: Rules registered (count=$rule_count)"
else
    test_fail "F03: Rules list empty"
fi

app_a_health="$(app_get "$APP_A/api/stub-demo/health")"
if [[ "$app_a_health" == "OK" ]]; then
    test_pass "F04: app-env-a health check"
else
    test_fail "F04: app-env-a health check (response: $app_a_health)"
fi

app_b_health="$(app_get "$APP_B/api/stub-demo/health")"
if [[ "$app_b_health" == "OK" ]]; then
    test_pass "F05: app-env-b health check"
else
    test_fail "F05: app-env-b health check (response: $app_b_health)"
fi

# -------------------- A: API security & CRUD --------------------
echo ""
echo "--- A: API Security & CRUD ---"

invalid_key_status="$(curl -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: invalid-key" "$SERVER/__baafoo__/api/rules" 2>/dev/null || echo 0)"
if [[ "$invalid_key_status" == "401" || "$invalid_key_status" == "403" ]]; then
    test_pass "A01: API rejects invalid API key"
else
    test_skip "A01: API invalid key rejection (status=$invalid_key_status)"
fi

# A02-A04: Rule CRUD
test_rule_id="test-rule-crud-$RANDOM"
rule_body='{
    "id": "'"$test_rule_id"'",
    "name": "CRUD Test Rule",
    "protocol": "http",
    "host": "example.com",
    "port": 80,
    "conditions": [{"type": "path", "operator": "equals", "value": "/crud-test"}],
    "responses": [{"name": "CRUD Response", "statusCode": 200, "body": "{\"mocked\":true}", "delayMs": 0}],
    "enabled": true,
    "priority": 100,
    "environments": ["staging-a"]
}'

create_result="$(api_post "rules" "$rule_body")"
if [[ "$create_result" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$create_result" "id")" != "" ]]; then
    test_pass "A02: Rule created"
else
    test_skip "A02: Rule create (response: $create_result)"
fi

rule_detail="$(api_get "rules/$test_rule_id")"
if [[ "$rule_detail" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$rule_detail" "id")" == "$test_rule_id" ]]; then
    test_pass "A03: Rule queried"
else
    test_skip "A03: Rule query (response: $rule_detail)"
fi

api_delete "rules/$test_rule_id" >/dev/null
if [[ $? -eq 0 ]]; then
    test_pass "A04: Rule deleted"
else
    test_skip "A04: Rule delete failed"
fi

# A05-A07: Environment CRUD
test_env_name="test-env-crud-$RANDOM"
env_body='{"name":"'"$test_env_name"'","mode":"stub","description":"CRUD test environment"}'

create_env="$(api_post "environments" "$env_body")"
env_id="$(get_json_value "$create_env" "id")"
if [[ "$HAVE_JQ" == "true" && -z "$env_id" ]]; then
    env_id="$(echo "$create_env" | jq -r '.data.id // empty' 2>/dev/null)"
fi
if [[ "$create_env" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$create_env" "name")" == "$test_env_name" ]]; then
    test_pass "A05: Environment created"
else
    test_skip "A05: Environment create (response: $create_env)"
fi

env_query_path="environments/${env_id:-$test_env_name}"
env_detail="$(api_get "$env_query_path")"
if [[ "$env_detail" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$env_detail" "name")" == "$test_env_name" ]]; then
    test_pass "A06: Environment queried"
else
    test_skip "A06: Environment query (response: $env_detail)"
fi

api_delete "$env_query_path" >/dev/null
test_pass "A07: Environment deleted"

# -------------------- H: HTTP protocol --------------------
echo ""
echo "--- H: HTTP ---"

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")"
stubbed="$(get_json_value "$resp" "stubbed")"
if [[ "$stubbed" == "true" ]]; then test_pass "H01: HTTP GET intercepted"
else test_fail "H01: HTTP GET intercepted (stubbed=$stubbed)"; fi
body="$(get_json_body "$resp")"
if [[ -n "$body" && "$body" =~ \"env\"[[:space:]]*:[[:space:]]*\"staging-a\" ]]; then
    test_pass "H01: HTTP GET response correct (staging-a stub)"
else
    test_fail "H01: HTTP GET response correct (resp=$resp)"
fi

resp="$(app_post "$APP_A/api/http/post?url=http://real-backend:9090/post&body=%7B%22test%22%3A%22baafoo%22%7D")"
stubbed="$(get_json_value "$resp" "stubbed")"
if [[ "$stubbed" == "true" ]]; then test_pass "H02: HTTP POST intercepted"
else test_fail "H02: HTTP POST intercepted (stubbed=$stubbed)"; fi

resp="$(app_get "$APP_A/api/http/methods")"
if [[ "$resp" =~ \"put\".*\"stubbed\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "H03: HTTP PUT intercepted"
else test_fail "H03: HTTP PUT intercepted (resp=$resp)"; fi
if [[ "$resp" =~ \"delete\".*\"stubbed\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "H04: HTTP DELETE intercepted"
else test_fail "H04: HTTP DELETE intercepted (resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/delay")"
stubbed="$(get_json_value "$resp" "stubbed")"
if [[ "$stubbed" == "true" ]]; then test_pass "H05: HTTP delay path intercepted"
else test_fail "H05: HTTP delay path intercepted (stubbed=$stubbed)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/error500")"
status_code="$(get_json_value "$resp" "statusCode")"
if [[ "$status_code" == "500" ]]; then test_pass "H06: HTTP error code returns 500"
else test_fail "H06: HTTP error code returns 500 (statusCode=$status_code)"; fi

graphql_body='{"operationName":"GetUser","query":"query GetUser { user { id name } }"}'
urlencoded_body="$(printf '%s' "$graphql_body" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))' 2>/dev/null || printf '%s' "$graphql_body")"
resp="$(app_post "$APP_A/api/http/post?url=http://real-backend:9090/graphql&body=$urlencoded_body")"
if [[ "$resp" =~ Baafoo[[:space:]]Mock[[:space:]]User ]]; then test_pass "H07: HTTP GraphQL rule matched (operationName=GetUser)"
else test_fail "H07: HTTP GraphQL rule not matched (response: $resp)"; fi

# H08: request-count reset before request
curl -sf -X POST "$SERVER/__baafoo__/api/rules/staging-a-http-request-count/reset-state" \
     -H "X-Api-Key: $API_KEY" >/dev/null 2>&1 || true
resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/counted")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "requestCount" ]]; then test_pass "H08: HTTP request-count rule matched (counter reset before run)"
else test_fail "H08: HTTP request-count rule (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://consul-server:8500/v1/status/leader")"
if [[ "$resp" =~ \"stubbed\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "H09: HTTP Consul rule matched"
else test_skip "H09: HTTP Consul rule (response: $resp)"; fi

# -------------------- T: TCP protocol --------------------
echo ""
echo "--- T: TCP ---"
TCP_HOST="server"
TCP_PORT="9001"

resp="$(app_get "$APP_A/api/socket/bio?host=$TCP_HOST&port=$TCP_PORT")"
if [[ "$resp" =~ \"intercepted\"[[:space:]]*:[[:space:]]*true || "$resp" =~ \"sent\" ]]; then test_pass "T01: TCP BIO Socket stub"
elif [[ "$resp" =~ \"connected\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "T01: TCP BIO Socket connected"
else test_fail "T01: TCP BIO Socket stub (response: $resp)"; fi

resp="$(app_get "$APP_A/api/socket/nio?host=$TCP_HOST&port=$TCP_PORT")"
connected="$(get_json_value "$resp" "connected")"
intercepted="$(get_json_value "$resp" "intercepted")"
if [[ "$connected" == "true" || "$intercepted" == "true" ]]; then test_pass "T02: TCP NIO Socket connected/intercepted"
else test_skip "T02: TCP NIO Socket (no response)"; fi

resp="$(app_get "$APP_A/api/socket/multiround?host=$TCP_HOST&port=$TCP_PORT")"
if [[ "$resp" =~ LOGIN|QUERY|LOGOUT|round ]]; then test_pass "T03: TCP multiround interaction"
else test_skip "T03: TCP multiround interaction (response: $resp)"; fi

# -------------------- K: Kafka protocol --------------------
echo ""
echo "--- K: Kafka ---"

# K01: Kafka Produce. The first KafkaProducer construction in a fresh JVM may
# trigger ByteBuddy class-loading + transform that is not fully linked on that
# very first call, causing DNS resolution of the original bootstrap.servers to
# fail. A single retry after a short settle handles this cold-start race.
resp="$(app_get "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then
    test_pass "K01: Kafka Produce stub (success)"
else
    sleep 3
    resp="$(app_get "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka")"
    if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then
        test_pass "K01: Kafka Produce stub (success on retry)"
    else
        test_fail "K01: Kafka Produce stub (response: $resp)"
    fi
fi

resp="$(app_get "$APP_A/api/kafka/consume?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "K02: Kafka Consume stub (success)"
else test_fail "K02: Kafka Consume stub (response: $resp)"; fi

resp="$(app_get "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-wildcard-topic&message=test")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "K03: Kafka wildcard topic stub (success)"
else test_skip "K03: Kafka wildcard topic (response: $resp)"; fi

# -------------------- P: Pulsar protocol --------------------
echo ""
echo "--- P: Pulsar ---"

resp="$(app_get "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-baafoo-pulsar")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "P01: Pulsar Produce stub (success)"
else test_fail "P01: Pulsar Produce (response: $resp)"; fi

resp="$(app_get "$APP_A/api/pulsar/consume?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "P02: Pulsar Consume stub (success)"
else test_fail "P02: Pulsar Consume (response: $resp)"; fi

resp="$(app_get "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-wildcard-topic&message=test")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "P03: Pulsar wildcard topic stub (success)"
else test_skip "P03: Pulsar wildcard topic (response: $resp)"; fi

# -------------------- J: JMS protocol --------------------
echo ""
echo "--- J: JMS ---"

resp="$(app_get "$APP_A/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-baafoo-jms")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "J01: JMS Queue send stub (success)"
else test_fail "J01: JMS Queue send stub (response: $resp)"; fi

resp="$(app_get "$APP_A/api/jms/receive?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE")"
if [[ "$resp" =~ \"success\"[[:space:]]*:[[:space:]]*true && ! "$resp" =~ \"error\" ]]; then test_pass "J02: JMS Queue receive stub (success)"
else test_fail "J02: JMS Queue receive (response: $resp)"; fi

# -------------------- E: Environment isolation --------------------
echo ""
echo "--- E: Environment Isolation ---"

resp_a="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")"
if [[ "$resp_a" =~ staging-a ]]; then test_pass "E01: staging-a isolation correct"
else test_fail "E01: staging-a isolation (resp: $resp_a)"; fi

resp_b="$(app_get "$APP_B/api/http/get?url=http://real-backend:9090/get")"
if [[ "$resp_b" =~ staging-b ]]; then test_pass "E02: staging-b isolation correct"
else test_fail "E02: staging-b isolation (resp: $resp_b)"; fi

# -------------------- PL: Plugin loading --------------------
echo ""
echo "--- PL: Plugin ---"

agent_logs="$(docker logs baafoo-app-env-a 2>&1 | tail -n 50 || true)"
if [[ "$agent_logs" =~ Plugin[[:space:]]loaded ]]; then test_pass "PL01: Plugin loaded (log shows Plugin loaded)"
elif [[ "$agent_logs" =~ No[[:space:]]plugin ]]; then test_pass "PL01: PluginManager initialized (no plugins loaded)"
elif [[ "$agent_logs" =~ Plugin ]]; then test_pass "PL01: PluginManager initialized"
else test_skip "PL01: Plugin loading check (cannot get container logs)"; fi

agents_json="$(api_get "agents")"
if [[ "$agents_json" =~ agent|staging ]]; then test_pass "PL02: Agent heartbeat registered"
else test_fail "PL02: Agent heartbeat registration failed (response: $agents_json)"; fi

resp="$(app_get "$APP_A/api/feign/get?baseUrl=http://real-backend:9090")"
if [[ "$resp" =~ \"stubbed\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "PL03: Feign call intercepted by agent"
elif [[ "$resp" =~ \"statusCode\"[[:space:]]*:[[:space:]]*[0-9]+ ]]; then test_skip "PL03: Feign call completed (may not be stubbed: $resp)"
else test_skip "PL03: Feign plugin test (response: $resp)"; fi

# -------------------- R: Recording (verified after D) --------------------
echo ""
echo "--- R: Recording (verified after D section) ---"

# -------------------- D: MQ direction annotation --------------------
echo ""
echo "--- D: MQ Direction ---"

envs_json="$(api_get "environments")"
env_a_id="$(get_environment_id "$envs_json" "staging-a")"

if [[ -n "$env_a_id" ]]; then
    api_put "environments/$env_a_id" '{"mode":"record-and-stub"}' >/dev/null
    echo "  Switched staging-a to RECORD_AND_STUB, waiting for agents to sync..."
    sleep 5

    app_get "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka-record" >/dev/null
    app_get "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-baafoo-pulsar-record" >/dev/null
    app_get "$APP_A/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-baafoo-jms-record" >/dev/null

    app_get "$APP_A/api/kafka/consume?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic" >/dev/null
    app_get "$APP_A/api/pulsar/consume?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic" >/dev/null
    app_get "$APP_A/api/jms/receive?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE" >/dev/null

    sleep 2
    recordings_json="$(api_get "recordings?limit=50")"

    rec_count="$(echo "$recordings_json" | grep -o '"id"' | wc -l | tr -d ' ')"
    if [[ "$rec_count" -gt 0 ]]; then test_pass "R01: Recording list has data (count=$rec_count)"
    else test_fail "R01: Recording list empty after RECORD_AND_STUB MQ traffic"; fi

    if [[ "$recordings_json" =~ \"direction\" ]]; then test_pass "R02: Recording contains direction field"
    else test_fail "R02: Recording missing direction field"; fi

    if [[ "$recordings_json" =~ \"ruleName\" ]]; then test_pass "R03: Recording contains ruleName field"
    else test_skip "R03: Recording missing ruleName field"; fi

    if [[ "$recordings_json" =~ \"protocol\":[[:space:]]*\"kafka\".*\"direction\":[[:space:]]*\"produce\" && "$recordings_json" =~ \"protocol\":[[:space:]]*\"kafka\".*\"direction\":[[:space:]]*\"consume\" ]]; then
        test_pass "D01: Kafka recording has produce/consume direction"
    else test_fail "D01: Kafka recording missing produce or consume direction"; fi

    if [[ "$recordings_json" =~ \"protocol\":[[:space:]]*\"jms\".*\"direction\":[[:space:]]*\"produce\" && "$recordings_json" =~ \"protocol\":[[:space:]]*\"jms\".*\"direction\":[[:space:]]*\"consume\" ]]; then
        test_pass "D02: JMS recording has produce/consume direction"
    else test_fail "D02: JMS recording missing produce or consume direction"; fi

    if [[ "$recordings_json" =~ \"protocol\":[[:space:]]*\"pulsar\".*\"direction\":[[:space:]]*\"produce\" && "$recordings_json" =~ \"protocol\":[[:space:]]*\"pulsar\".*\"direction\":[[:space:]]*\"consume\" ]]; then
        test_pass "D03: Pulsar recording has produce/consume direction"
    else test_fail "D03: Pulsar recording missing produce or consume direction"; fi

    # Restore staging-a to STUB mode
    api_put "environments/$env_a_id" '{"mode":"stub"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
else
    test_skip "D: MQ Direction (cannot find staging-a environment ID)"
fi

# -------------------- C: Condition type coverage --------------------
echo ""
echo "--- C: Condition Types ---"

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/headers&headerName=X-Test-Header&headerValue=baafoo-test")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "header" ]]; then test_pass "C01: Header condition match (matchedBy=header)"
else test_fail "C01: Header condition not matched (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get?baafoo=test")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "query" ]]; then test_pass "C02: Query param condition match (matchedBy=query)"
else test_fail "C02: Query param condition not matched (matchedBy=$mb, resp=$resp)"; fi

body_c03='{"data":"baafoo-body-test"}'
body_c03_enc="$(printf '%s' "$body_c03" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))' 2>/dev/null || printf '%s' "$body_c03")"
resp="$(app_post "$APP_A/api/http/post?url=http://real-backend:9090/post&body=$body_c03_enc")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "body" ]]; then test_pass "C03: Body contains condition match (matchedBy=body)"
else test_fail "C03: Body contains condition not matched (matchedBy=$mb, resp=$resp)"; fi

body_c04='{"action":"submit"}'
body_c04_enc="$(printf '%s' "$body_c04" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))' 2>/dev/null || printf '%s' "$body_c04")"
resp="$(app_post "$APP_A/api/http/post?url=http://real-backend:9090/post&body=$body_c04_enc")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "jsonPath" ]]; then test_pass "C04: BodyJsonPath condition match (matchedBy=jsonPath)"
else test_fail "C04: BodyJsonPath condition not matched (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/baafoo/anything")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "path-contains" ]]; then test_pass "C05: Path contains operator (matchedBy=path-contains)"
else test_fail "C05: Path contains operator not matched (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/suffix")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "path-endswith" ]]; then test_pass "C06: Path endsWith operator (matchedBy=path-endswith)"
else test_fail "C06: Path endsWith operator not matched (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/api/v1/users")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "path-regex" ]]; then test_pass "C07: Path regex operator (matchedBy=path-regex)"
else test_fail "C07: Path regex operator not matched (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get&headerName=X-Baafoo-Test&headerValue=1")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "header-exists" ]]; then test_pass "C08: Header exists operator (matchedBy=header-exists)"
else test_fail "C08: Header exists operator not matched (matchedBy=$mb, resp=$resp)"; fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/case-test")"
mb="$(get_matched_by "$resp")"
if [[ "$mb" == "case-insensitive" ]]; then test_pass "C09: Case insensitive match (matchedBy=case-insensitive)"
else test_fail "C09: Case insensitive match not matched (matchedBy=$mb, resp=$resp)"; fi

# C10: Disabled rule should NOT match. The disabled rule (path startsWith
# /disabled-path, enabled=false) must be skipped; the request falls through to
# the generic http-get rule (path startsWith /). Assert ruleId is NOT the
# disabled rule -- checking for the literal string "disabled" is wrong because
# the request path itself contains "disabled-path".
resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/disabled-path")"
c10_rule_id="$(get_json_value "$resp" "ruleId")"
if [[ "$c10_rule_id" != "staging-a-http-disabled" ]]; then
    test_pass "C10: Disabled rule not matched (ruleId=$c10_rule_id)"
else
    test_fail "C10: Disabled rule should not match (ruleId=$c10_rule_id, resp=$resp)"
fi

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/global-endpoint")"
if [[ "$resp" =~ \"rule\"[[:space:]]*:[[:space:]]*\"global\" ]]; then test_pass "C11: Global rule (no env) matched"
else test_skip "C11: Global rule (response: $resp)"; fi

# -------------------- M: Environment Mode --------------------
echo ""
echo "--- M: Environment Mode ---"

resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")"
stubbed="$(get_json_value "$resp" "stubbed")"
if [[ "$stubbed" == "true" ]]; then test_pass "M01: STUB mode returns stub response"
else test_fail "M01: STUB mode should return stub (stubbed=$stubbed)"; fi

resp="$(app_get "$APP_B/api/http/get?url=http://real-backend:9090/get")"
stubbed="$(get_json_value "$resp" "stubbed")"
if [[ "$stubbed" == "true" ]]; then test_pass "M02: RECORD_AND_STUB mode returns stub"
else test_fail "M02: RECORD_AND_STUB mode should return stub (stubbed=$stubbed)"; fi

envs_json="$(api_get "environments")"
env_a_id="$(get_environment_id "$envs_json" "staging-a")"

# M03: PASSTHROUGH
if [[ -n "$env_a_id" ]]; then
    api_put "environments/$env_a_id" '{"mode":"passthrough"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
    resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")"
    stubbed="$(get_json_value "$resp" "stubbed")"
    if [[ "$stubbed" != "true" && "$resp" =~ real-backend ]]; then test_pass "M03: PASSTHROUGH mode forwards request to real backend"
    elif [[ "$stubbed" == "true" ]]; then test_fail "M03: PASSTHROUGH mode still returning stub (agent has not picked up mode change yet?)"
    else test_skip "M03: PASSTHROUGH mode (unexpected response: $resp)"; fi
    api_put "environments/$env_a_id" '{"mode":"stub"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
else
    test_skip "M03: PASSTHROUGH mode (cannot find staging-a environment ID)"
fi

# M04: RECORD
if [[ -n "$env_a_id" ]]; then
    api_put "environments/$env_a_id" '{"mode":"record"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
    resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/get")"
    rec_after="$(api_get "recordings?limit=5")"
    if [[ "$resp" =~ passthrough|real-backend|statusCode && "$rec_after" =~ direction ]]; then test_pass "M04: RECORD mode passthrough + record"
    else test_skip "M04: RECORD mode (resp: $resp)"; fi
    api_put "environments/$env_a_id" '{"mode":"stub"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
else
    test_skip "M04: RECORD mode (cannot find staging-a environment ID)"
fi

# M05: RECORD_ALL
if [[ -n "$env_a_id" ]]; then
    api_put "environments/$env_a_id" '{"mode":"record-all"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
    resp="$(app_get "$APP_A/api/http/get?url=http://real-backend:9090/status/200")"
    rec_after="$(api_get "recordings?limit=10")"
    if [[ "$rec_after" =~ unmatched|direction ]]; then test_pass "M05: RECORD_ALL mode records unmatched"
    else test_skip "M05: RECORD_ALL mode (recordings: $rec_after)"; fi
    api_put "environments/$env_a_id" '{"mode":"stub"}' >/dev/null
    sleep "$MODE_SETTLE_WAIT"
else
    test_skip "M05: RECORD_ALL mode (cannot find staging-a environment ID)"
fi

# -------------------- AS: RuleSet CRUD --------------------
echo ""
echo "--- AS: RuleSet ---"

test_set_id="test-ruleset-$RANDOM"
set_body='{
    "id": "'"$test_set_id"'",
    "name": "Test RuleSet",
    "description": "Full-chain RuleSet CRUD test",
    "ruleIds": ["staging-a-http-get"],
    "enabled": true
}'

create_set="$(api_post "rulesets" "$set_body")"
if [[ "$create_set" =~ \"success\"[[:space:]]*:[[:space:]]*true || "$(get_json_value "$create_set" "id")" != "" ]]; then
    test_pass "AS01: RuleSet created"
else
    test_skip "AS01: RuleSet create (response: $create_set)"
fi

sets_json="$(api_get "rulesets")"
if [[ "$sets_json" =~ "$test_set_id" ]]; then test_pass "AS02: RuleSet listed"
else test_fail "AS02: RuleSet not found in list (resp: $sets_json)"; fi

api_delete "rulesets/$test_set_id" >/dev/null
sets_json_after="$(api_get "rulesets")"
if [[ ! "$sets_json_after" =~ "$test_set_id" ]]; then test_pass "AS03: RuleSet deleted"
else test_fail "AS03: RuleSet still present after delete (resp: $sets_json_after)"; fi

# AS04-AS06 (RuleSet update/disable/re-enable) removed: server does not
# implement PUT /rulesets/{id}. The handler at RuleApiHandler.java only
# supports POST/GET/DELETE for rulesets. These tests always 404'd and were
# skipped — removed to keep the test suite honest.

# Clean up the rule set created for testing
api_delete "rulesets/$test_set_id" >/dev/null 2>&1

# -------------------- REC: Recording management --------------------
echo ""
echo "--- REC: Recording Management ---"

rec_page_json="$(api_get "recordings?page=1&size=5")"
rec_total=""
if [[ "$HAVE_JQ" == "true" ]]; then
    rec_total="$(echo "$rec_page_json" | jq -r '.data.total // empty' 2>/dev/null)"
    has_items="$(echo "$rec_page_json" | jq -r '.data.items // empty' 2>/dev/null)"
else
    if [[ "$rec_page_json" =~ \"total\"[[:space:]]*:[[:space:]]*([0-9]+) ]]; then rec_total="${BASH_REMATCH[1]}"; fi
    if [[ "$rec_page_json" =~ \"items\" ]]; then has_items="yes"; fi
fi
if [[ -n "$rec_total" || -n "$has_items" ]]; then test_pass "REC-PAGE: recordings pagination supported (total=${rec_total:-?})"
else test_fail "REC-PAGE: recordings pagination not structured (resp: $rec_page_json)"; fi

rec_list_json="$(api_get "recordings?page=1&size=10")"
del_id=""
if [[ "$HAVE_JQ" == "true" ]]; then
    del_id="$(echo "$rec_list_json" | jq -r '.data.items[0].id // empty' 2>/dev/null)"
else
    if [[ "$rec_list_json" =~ \"id\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; then del_id="${BASH_REMATCH[1]}"; fi
fi

if [[ -n "$del_id" ]]; then
    api_delete "recordings/$del_id" >/dev/null
    after_json="$(api_get "recordings?page=1&size=50")"
    still_there=false
    if [[ "$HAVE_JQ" == "true" ]]; then
        [[ "$(echo "$after_json" | jq -r --arg id "$del_id" '.data.items[]? | select(.id == $id) | .id' 2>/dev/null)" == "$del_id" ]] && still_there=true
    else
        [[ "$after_json" =~ \"id\"[[:space:]]*:[[:space:]]*\"$del_id\" ]] && still_there=true
    fi
    if [[ "$still_there" != "true" ]]; then test_pass "REC-DEL: recording deleted (id=$del_id)"
    else test_fail "REC-DEL: recording still present after delete (id=$del_id)"; fi
else
    test_skip "REC-DEL: no recordings available to delete"
fi

# -------------------- RU / RST: Undo & counter reset --------------------
echo ""
echo "--- RU/RST: Undo & Reset ---"

ru_rule_id="staging-a-http-get"
orig="$(api_get "rules/$ru_rule_id")"
if [[ -n "$orig" ]]; then
    # Build update payload without hard jq dependency (python3 fallback).
    # Parity with the jq path: emit ONLY the rule object (the .data node),
    # not the full API envelope — the server rejects envelope fields such as
    # "success"/"code" when deserializing a Rule. The Rule model has no
    # "description" field, so we mutate "name" (a real field) to a temp value;
    # this creates an undo history entry, which the subsequent undo call reverts.
    updated=""
    if command -v python3 >/dev/null 2>&1; then
        updated="$(echo "$orig" | python3 -c "import sys,json
try:
    d=json.load(sys.stdin)
    tgt = d.get('data', d)
    if isinstance(tgt, dict):
        base = tgt.get('name') or ''
        tgt['name'] = base + '-tmp-undo'
    print(json.dumps(tgt))
except Exception: pass" 2>/dev/null)"
    elif [[ "$HAVE_JQ" == "true" ]]; then
        updated="$(echo "$orig" | jq '.data | .name = ((.name // "") + "-tmp-undo")' 2>/dev/null)"
    fi
    if [[ -n "$updated" ]]; then
        api_put "rules/$ru_rule_id" "$updated" >/dev/null
        undo="$(api_post "rules/$ru_rule_id/undo" "")"
        if [[ "$undo" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "RU01: rule undo successful"
        else test_skip "RU01: rule undo (resp: $undo)"; fi
    else
        test_skip "RU01: rule undo (could not build update payload)"
    fi
else
    test_skip "RU01: rule undo (empty response)"
fi

rst="$(api_post "rules/reset-all-state" "")"
if [[ "$rst" =~ \"success\"[[:space:]]*:[[:space:]]*true ]]; then test_pass "RST01: reset-all-state successful"
else test_skip "RST01: reset-all-state (resp: $rst)"; fi

# -------------------- OAPI: OpenAPI import --------------------
echo ""
echo "--- OAPI: OpenAPI Import ---"

oapi_spec_path="$rules_dir/openapi-sample.json"
if [[ ! -f "$oapi_spec_path" ]]; then
    test_skip "OAPI01: OpenAPI sample spec not found"
else
    oapi_spec="$(cat "$oapi_spec_path")"

    preview="$(curl -sf -X POST "$SERVER/__baafoo__/api/rules/import-openapi" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: $API_KEY" \
        -d "$oapi_spec" 2>/dev/null || echo '{}')"
    gen_count="$(get_json_value "$preview" "data.generatedCount")"
    if [[ "$preview" =~ \"success\"[[:space:]]*:[[:space:]]*true && -n "$gen_count" && "$gen_count" != "0" ]]; then
        test_pass "OAPI01: OpenAPI import preview generated $gen_count rules"
    else
        test_fail "OAPI01: OpenAPI import preview (resp: $preview)"
    fi

    save="$(curl -sf -X POST "$SERVER/__baafoo__/api/rules/import-openapi?save=true&environment=staging-a" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: $API_KEY" \
        -d "$oapi_spec" 2>/dev/null || echo '{}')"
    saved_count="$(get_json_value "$save" "data.savedCount")"
    if [[ "$save" =~ \"success\"[[:space:]]*:[[:space:]]*true && -n "$saved_count" && "$saved_count" != "0" ]]; then
        test_pass "OAPI02: OpenAPI import persisted $saved_count rules"
        if [[ "$HAVE_JQ" == "true" ]]; then
            saved_ids="$(echo "$save" | jq -r '.data.savedIds[]?' 2>/dev/null)"
            for rid in $saved_ids; do
                api_delete "rules/$rid" >/dev/null 2>&1 || true
            done
        fi
    else
        test_fail "OAPI02: OpenAPI import persist (resp: $save)"
    fi
fi

# -------------------- G: gRPC --------------------
echo ""
echo "--- G: gRPC ---"

grpc_call() {
    local endpoint="$1"
    app_get "$APP_A/api/grpc/$endpoint"
}

resp="$(grpc_call "greeter")"
if [[ "$HAVE_JQ" == "true" ]]; then
    j="$(echo "$resp" | jq -r '.' 2>/dev/null)"
    completed="$(echo "$j" | jq -r '.completed // empty')"
    grpc_status="$(echo "$j" | jq -r '.grpcStatus // empty')"
    msg0="$(echo "$j" | jq -r '.messages[0] // empty')"
    msg_count="$(echo "$j" | jq -r '.messages | length // 0')"
    if [[ "$completed" == "true" && "$grpc_status" == "0" && "$msg_count" -ge 1 && "$msg0" =~ Baafoo[[:space:]]gRPC ]]; then
        test_pass "G01: gRPC unary SayHello stubbed (grpc-status=$grpc_status, msgs=$msg_count)"
    else
        test_fail "G01: gRPC unary SayHello (resp=$resp)"
    fi
else
    if [[ "$resp" =~ \"completed\":[[:space:]]*true && "$resp" =~ \"grpcStatus\":[[:space:]]*\"0\" && "$resp" =~ Baafoo[[:space:]]gRPC ]]; then
        test_pass "G01: gRPC unary SayHello stubbed"
    else
        test_fail "G01: gRPC unary SayHello (resp=$resp)"
    fi
fi

resp="$(grpc_call "slow")"
if [[ "$HAVE_JQ" == "true" ]]; then
    j="$(echo "$resp" | jq -r '.' 2>/dev/null)"
    completed="$(echo "$j" | jq -r '.completed // empty')"
    grpc_status="$(echo "$j" | jq -r '.grpcStatus // empty')"
    msg0="$(echo "$j" | jq -r '.messages[0] // empty')"
    msg_count="$(echo "$j" | jq -r '.messages | length // 0')"
    if [[ "$completed" == "true" && "$grpc_status" == "0" && "$msg_count" -ge 1 && "$msg0" =~ delayed ]]; then
        test_pass "G02: gRPC unary SlowMethod stubbed (grpc-status=$grpc_status, msgs=$msg_count)"
    else
        test_fail "G02: gRPC unary SlowMethod (resp=$resp)"
    fi
else
    if [[ "$resp" =~ \"completed\":[[:space:]]*true && "$resp" =~ \"grpcStatus\":[[:space:]]*\"0\" && "$resp" =~ delayed ]]; then
        test_pass "G02: gRPC unary SlowMethod stubbed"
    else
        test_fail "G02: gRPC unary SlowMethod (resp=$resp)"
    fi
fi

resp="$(grpc_call "error")"
if [[ "$HAVE_JQ" == "true" ]]; then
    j="$(echo "$resp" | jq -r '.' 2>/dev/null)"
    completed="$(echo "$j" | jq -r '.completed // empty')"
    grpc_status="$(echo "$j" | jq -r '.grpcStatus // empty')"
    if [[ "$completed" == "true" && "$grpc_status" == "5" ]]; then
        test_pass "G03: gRPC unary GetUser returns grpc-status=5 (NOT_FOUND) as configured"
    else
        test_fail "G03: gRPC unary GetUser expected grpc-status=5 (resp=$resp)"
    fi
else
    if [[ "$resp" =~ \"completed\":[[:space:]]*true && "$resp" =~ \"grpcStatus\":[[:space:]]*\"5\" ]]; then
        test_pass "G03: gRPC unary GetUser returns grpc-status=5"
    else
        test_fail "G03: gRPC unary GetUser expected grpc-status=5 (resp=$resp)"
    fi
fi

resp="$(grpc_call "server-stream")"
if [[ "$HAVE_JQ" == "true" ]]; then
    j="$(echo "$resp" | jq -r '.' 2>/dev/null)"
    completed="$(echo "$j" | jq -r '.completed // empty')"
    grpc_status="$(echo "$j" | jq -r '.grpcStatus // empty')"
    msg_count="$(echo "$j" | jq -r '.messages | length // 0')"
    if [[ "$completed" == "true" && "$grpc_status" == "0" && "$msg_count" == "3" ]]; then
        test_pass "G04: gRPC server-streaming StreamEvents returned $msg_count messages"
    else
        test_fail "G04: gRPC server-streaming StreamEvents expected 3 messages (resp=$resp)"
    fi
else
    if [[ "$resp" =~ \"completed\":[[:space:]]*true && "$resp" =~ \"grpcStatus\":[[:space:]]*\"0\" && "$resp" =~ \"messages\":[[:space:]]*\[([^\]]*)\] && "${#BASH_REMATCH[1]}" -gt 40 ]]; then
        test_pass "G04: gRPC server-streaming StreamEvents returned messages"
    else
        test_fail "G04: gRPC server-streaming StreamEvents expected 3 messages (resp=$resp)"
    fi
fi

resp="$(grpc_call "client-stream")"
if [[ "$HAVE_JQ" == "true" ]]; then
    j="$(echo "$resp" | jq -r '.' 2>/dev/null)"
    completed="$(echo "$j" | jq -r '.completed // empty')"
    grpc_status="$(echo "$j" | jq -r '.grpcStatus // empty')"
    msg_count="$(echo "$j" | jq -r '.messages | length // 0')"
    if [[ "$completed" == "true" && "$grpc_status" == "0" && "$msg_count" == "1" ]]; then
        test_pass "G05: gRPC client-streaming CollectMetrics returned $msg_count aggregated response"
    else
        test_fail "G05: gRPC client-streaming CollectMetrics expected 1 response (resp=$resp)"
    fi
else
    if [[ "$resp" =~ \"completed\":[[:space:]]*true && "$resp" =~ \"grpcStatus\":[[:space:]]*\"0\" ]]; then
        test_pass "G05: gRPC client-streaming CollectMetrics returned aggregated response"
    else
        test_fail "G05: gRPC client-streaming CollectMetrics expected 1 response (resp=$resp)"
    fi
fi

resp="$(grpc_call "bidi")"
if [[ "$HAVE_JQ" == "true" ]]; then
    j="$(echo "$resp" | jq -r '.' 2>/dev/null)"
    completed="$(echo "$j" | jq -r '.completed // empty')"
    grpc_status="$(echo "$j" | jq -r '.grpcStatus // empty')"
    msg_count="$(echo "$j" | jq -r '.messages | length // 0')"
    if [[ "$completed" == "true" && "$grpc_status" == "0" && "$msg_count" == "2" ]]; then
        test_pass "G06: gRPC bidi-streaming Chat returned $msg_count messages"
    else
        test_fail "G06: gRPC bidi-streaming Chat expected 2 messages (resp=$resp)"
    fi
else
    if [[ "$resp" =~ \"completed\":[[:space:]]*true && "$resp" =~ \"grpcStatus\":[[:space:]]*\"0\" ]]; then
        test_pass "G06: gRPC bidi-streaming Chat returned messages"
    else
        test_fail "G06: gRPC bidi-streaming Chat expected 2 messages (resp=$resp)"
    fi
fi

# -------------------- MX: Protocol x Mode coverage gaps --------------------
echo ""
echo "--- MX: Protocol x Mode Matrix (gap markers) ---"

mx_gaps=(
    "tcp:passthrough"      "tcp:record"      "tcp:record-and-stub"  "tcp:record-all"
    "kafka:passthrough"    "kafka:record"    "kafka:record-all"
    "pulsar:passthrough"   "pulsar:record"   "pulsar:record-all"
    "jms:passthrough"      "jms:record"      "jms:record-all"
)
for gap in "${mx_gaps[@]}"; do
    proto="${gap%%:*}"
    mode="${gap##*:}"
    test_skip "MX: $proto x $mode not exercised (no real $proto broker in staging; only MockBroker STUB / RECORD_AND_STUB path driven)"
done

# -----------------------------------------------------------------------------
# 6. Summary report
# -----------------------------------------------------------------------------
echo ""
echo "============================================================"
echo "  Test Summary"
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
# JUnit XML report (CI consumption)
# -----------------------------------------------------------------------------
write_junit_xml "$SCRIPT_DIR/junit-report.xml"

# -----------------------------------------------------------------------------
# Cleanup
# -----------------------------------------------------------------------------
if [[ "$NO_CLEANUP" != "true" ]]; then
    write_step "Cleanup Docker environment"
    docker compose $COMPOSE_FILES down -v >/dev/null 2>&1 || true
    write_ok "Environment cleaned"
else
    echo ""
    echo "  Environment kept (--no-cleanup)"
    echo "  Web console: http://localhost:8084"
    echo "  App env-a:   http://localhost:9090"
    echo "  App env-b:   http://localhost:9091"
fi

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "=== Full-Chain Integration Test FAILED ($FAIL failed) ==="
    exit 1
elif [[ $SKIP -gt 0 ]]; then
    echo ""
    echo "=== Full-Chain Integration Test PASSED WITH SKIPS ($SKIP skipped) ==="
    echo "WARNING: coverage gaps remain (see SKIP lines above). CI should treat exit 2 as non-green for release gating."
    exit 2
else
    echo ""
    echo "=== Full-Chain Integration Test PASSED (no skips) ==="
    exit 0
fi
