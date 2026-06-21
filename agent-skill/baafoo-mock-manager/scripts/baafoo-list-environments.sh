#!/usr/bin/env bash
# List all environments
# Usage: baafoo-list-environments.sh
set -euo pipefail
PARAMS='{"name":"list_environments","arguments":{}}'
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
