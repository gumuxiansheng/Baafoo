#!/usr/bin/env bash
# Baafoo MCP call — send JSON-RPC request to Baafoo MCP Server
# Usage: baafoo-call.sh <method> [json-params] [id]
# Example: baafoo-call.sh tools/call '{"name":"list_rules","arguments":{}}' 1

set -euo pipefail

BAAFOO_URL="${BAAFOO_URL:-http://localhost:8084}"
BAAFOO_API_KEY="${BAAFOO_API_KEY:-}"
BAAFOO_TOKEN="${BAAFOO_TOKEN:-}"

METHOD="${1:?Method required (e.g. tools/call)}"
PARAMS="${2:-{}}"
ID="${3:-1}"

AUTH_HEADER=""
if [[ -n "$BAAFOO_TOKEN" ]]; then
  AUTH_HEADER="Authorization: Bearer $BAAFOO_TOKEN"
elif [[ -n "$BAAFOO_API_KEY" ]]; then
  AUTH_HEADER="X-Api-Key: $BAAFOO_API_KEY"
fi

PAYLOAD=$(cat <<EOF
{"jsonrpc":"2.0","id":$ID,"method":"$METHOD","params":$PARAMS}
EOF
)

if [[ -n "$AUTH_HEADER" ]]; then
  curl -s -X POST "$BAAFOO_URL/__baafoo__/api/mcp" \
    -H "Content-Type: application/json" \
    -H "$AUTH_HEADER" \
    -d "$PAYLOAD"
else
  curl -s -X POST "$BAAFOO_URL/__baafoo__/api/mcp" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
fi
