#!/usr/bin/env bash
# Create a new Mock rule from JSON file
# Usage: baafoo-create-rule.sh <rule.json>
set -euo pipefail
FILE="${1:?Rule JSON file required}"
RULE_JSON=$(cat "$FILE")
PARAMS="{\"name\":\"create_rule\",\"arguments\":$RULE_JSON}"
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
