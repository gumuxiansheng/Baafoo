#!/usr/bin/env bash
# List all Mock rules with optional pagination
# Usage: baafoo-list-rules.sh [page] [size] [protocol] [keyword]
set -euo pipefail

PAGE="${1:-1}"
SIZE="${2:-20}"
PROTOCOL="${3:-}"
KEYWORD="${4:-}"

FILTERS=""
[[ -n "$PROTOCOL" ]] && FILTERS="\"protocol\":\"$PROTOCOL\","
[[ -n "$KEYWORD" ]] && FILTERS="${FILTERS}\"keyword\":\"$KEYWORD\","

PARAMS="{\"name\":\"list_rules\",\"arguments\":{\"page\":$PAGE,\"size\":$SIZE${FILTERS%,}}}"
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
