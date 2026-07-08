#!/usr/bin/env bash
set -euo pipefail

MODULE="baafoo-core"
EXTRA_ARGS=""

show_help() {
    cat <<'EOF'
Usage: ./run-mutation.sh [--module <module>] [--extra-args <args>] [--help]

Run mutation testing (PIT) with JDK 8.

Examples:
    ./run-mutation.sh                                         # baafoo-core
    ./run-mutation.sh --module baafoo-core
    ./run-mutation.sh --module baafoo-server
    ./run-mutation.sh --module baafoo-server --extra-args "-Dpitest.mutationThreshold=15"

Prerequisites:
    1. Copy testing/1_UnitTest/toolchains.xml to ~/.m2/toolchains.xml (edit the JDK path)
    2. Or set JAVA_HOME to JDK 8 before running
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --module)
            MODULE="$2"
            shift 2
            ;;
        --extra-args)
            EXTRA_ARGS="$2"
            shift 2
            ;;
        --help|-h)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

JDK8_PATHS=(
    "C:/Program Files/Java/jdk1.8.0_202"
    "/usr/lib/jvm/java-8-openjdk"
    "/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home"
)

FOUND_JDK8=""
for path in "${JDK8_PATHS[@]}"; do
    if [[ -d "$path" ]]; then
        FOUND_JDK8="$path"
        break
    fi
done

if [[ -n "$FOUND_JDK8" ]]; then
    echo "Using JDK 8 at: $FOUND_JDK8"
    export JAVA_HOME="$FOUND_JDK8"
else
    echo "JDK 8 not found at common paths. Using current JAVA_HOME (${JAVA_HOME:-not set})."
    echo "Set JAVA_HOME to a JDK 8 installation before running."
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# shellcheck disable=SC2086
mvn org.pitest:pitest-maven:mutationCoverage -pl "$MODULE" -am $EXTRA_ARGS
