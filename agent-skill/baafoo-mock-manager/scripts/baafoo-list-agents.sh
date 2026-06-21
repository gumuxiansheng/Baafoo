#!/usr/bin/env bash
# List all registered agents
# Usage: baafoo-list-agents.sh
set -euo pipefail
PARAMS='{"name":"list_agents","arguments":{}}'
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
