#!/usr/bin/env bash
# List all scene sets
# Usage: baafoo-list-scenes.sh
set -euo pipefail
PARAMS='{"name":"list_scenes","arguments":{}}'
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
