#!/usr/bin/env bash
# Export rules as OpenAPI 3.0
# Usage: baafoo-export-openapi.sh [environment]
set -euo pipefail
ENV="${1:-}"
ARGS=""
[[ -n "$ENV" ]] && ARGS="\"environment\":\"$ENV\","
PARAMS="{\"name\":\"export_openapi\",\"arguments\":{$ARGS%*}}}"
PARAMS=$(echo "$PARAMS" | sed 's/,%*//; s/%*//')
exec "$(dirname "$0")/baafoo-call.sh" tools/call "$PARAMS" 1
