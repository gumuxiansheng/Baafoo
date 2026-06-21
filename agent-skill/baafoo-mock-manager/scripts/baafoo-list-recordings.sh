#!/usr/bin/env bash
# List recordings with optional filters
# Usage: baafoo-list-recordings.sh [rule-id] [limit]
set -euo pipefail
RULE_ID="${1:-}"
LIMIT="${2:-20}"

ARGS=""
[[ -n "$RULE_ID" ]] && ARGS="\"ruleId\":\"$RULE_ID\","
ARGS="${ARGS}\"limit\":$LIMIT"

PARAMS="{\"name\":\"list_recordings\",\"arguments\":{$ARGS}}"
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
