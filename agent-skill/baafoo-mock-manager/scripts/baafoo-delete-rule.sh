#!/usr/bin/env bash
# Delete a rule by ID
# Usage: baafoo-delete-rule.sh <rule-id>
set -euo pipefail
ID="${1:?Rule ID required}"
PARAMS="{\"name\":\"delete_rule\",\"arguments\":{\"id\":\"$ID\"}}"
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
