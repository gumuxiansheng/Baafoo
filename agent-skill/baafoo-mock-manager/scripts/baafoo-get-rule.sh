#!/usr/bin/env bash
# Get rule detail by ID
# Usage: baafoo-get-rule.sh <rule-id>
set -euo pipefail
ID="${1:?Rule ID required}"
PARAMS="{\"name\":\"get_rule\",\"arguments\":{\"id\":\"$ID\"}}"
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
