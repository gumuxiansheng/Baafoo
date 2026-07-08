#!/bin/sh
set -e
BASE_URL="http://localhost:8084/__baafoo__/api"
API_KEY="staging-admin-key"
RULES_FILE="$1"
if [ -z "$RULES_FILE" ]; then
    RULES_FILE="testing/2_IntegrationTest/rules/all-protocols-rules.json"
fi

if [ ! -f "$RULES_FILE" ]; then
    echo "Rules file not found: $RULES_FILE"
    exit 1
fi

echo "Registering rules from $RULES_FILE ..."

# Use jq to iterate over array elements if available; otherwise fallback to python3
if command -v jq >/dev/null 2>&1; then
    total=$(jq 'length' "$RULES_FILE")
    i=0
    while [ "$i" -lt "$total" ]; do
        rule=$(jq -c ".[$i]" "$RULES_FILE")
        id=$(echo "$rule" | jq -r '.id')
        resp=$(curl -sf -X POST "$BASE_URL/rules" \
            -H "Content-Type: application/json" \
            -H "X-Api-Key: $API_KEY" \
            -d "$rule")
        echo "$id: $resp"
        i=$((i + 1))
    done
elif command -v python3 >/dev/null 2>&1; then
    python3 - <<PY
import json, subprocess, sys
with open("$RULES_FILE", "r", encoding="utf-8") as f:
    rules = json.load(f)
for rule in rules:
    rule_json = json.dumps(rule, ensure_ascii=False)
    result = subprocess.run([
        "curl", "-sf", "-X", "POST", "$BASE_URL/rules",
        "-H", "Content-Type: application/json",
        "-H", "X-Api-Key: $API_KEY",
        "-d", rule_json
    ], capture_output=True, text=True)
    print(f"{rule['id']}: {result.stdout or result.stderr}")
PY
else
    echo "jq or python3 is required"
    exit 1
fi

echo "Done."
