#!/usr/bin/env bash
# Get system status
# Usage: baafoo-status.sh
set -euo pipefail
PARAMS='{"name":"get_system_status","arguments":{}}'
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
