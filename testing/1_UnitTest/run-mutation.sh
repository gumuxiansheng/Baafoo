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
    "/usr/lib/jvm/temurin-8-jdk"*
    "/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home"
)

FOUND_JDK8=""
for path in "${JDK8_PATHS[@]}"; do
    # Expand globs (e.g. temurin-8-jdk-amd64) if present.
    for expanded in $path; do
        if [[ -d "$expanded" ]]; then
            FOUND_JDK8="$expanded"
            break 2
        fi
    done
done

if [[ -n "$FOUND_JDK8" ]]; then
    echo "Using JDK 8 at: $FOUND_JDK8"
    export JAVA_HOME="$FOUND_JDK8"
elif [[ -n "${JAVA_HOME:-}" ]] && ("$JAVA_HOME/bin/java" -version 2>&1 | grep -Eq "1\.8\.|version \"8\."); then
    # GitHub Actions / CNB set a JDK 8 JAVA_HOME explicitly — reuse it.
    echo "Using JDK 8 from JAVA_HOME: $JAVA_HOME"
else
    echo "WARNING: JDK 8 not found at common paths and JAVA_HOME does not look like JDK 8."
    echo "         Current JAVA_HOME=${JAVA_HOME:-not set}. PIT requires JDK 8; continuing anyway."
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# PROJECT_ROOT is the repository root — the directory holding the baafoo-parent
# aggregator pom.xml (the one that declares the <modules> including baafoo-core).
# This script lives at testing/1_UnitTest/, so the root is two levels up. We
# walk up defensively in case the script is relocated, because running
# `mvn -pl baafoo-core` from any other directory makes Maven fail with
# "Could not find the selected project in the reactor: baafoo-core".
PROJECT_ROOT="$SCRIPT_DIR"
while [[ ! -f "$PROJECT_ROOT/pom.xml" ]] || ! grep -q "baafoo-parent" "$PROJECT_ROOT/pom.xml" 2>/dev/null; do
    PARENT="$(cd "$PROJECT_ROOT/.." && pwd)"
    if [[ "$PARENT" == "$PROJECT_ROOT" ]]; then
        echo "ERROR: could not locate the baafoo-parent pom.xml (repository root)." >&2
        exit 1
    fi
    PROJECT_ROOT="$PARENT"
done
cd "$PROJECT_ROOT"

# Use a system `mvn` when available (CNB's maven image), otherwise fall back to
# the Maven wrapper ./mvnw (GitHub Actions, which has no system maven).
if command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
elif [[ -x "./mvnw" ]]; then
    MVN="./mvnw"
else
    echo "ERROR: neither mvn nor ./mvnw found." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Two-step build (required for inter-module dependencies like baafoo-plugin-api)
#
# Running `mvn org.pitest:pitest-maven:mutationCoverage -pl <module> -am` invokes
# ONLY the mutationCoverage goal on each reactor module, so upstream modules are
# never packaged/installed. The target module then fails to resolve them:
#   Could not find artifact com.baafoo:baafoo-plugin-api:jar:1.1.0-SNAPSHOT
#
# Step 1 installs the module and its upstream dependencies (with tests skipped,
# since PIT runs the tests itself in step 2) so their jars land in the local
# Maven repo. Step 2 runs PIT on the target module only, which now resolves its
# dependencies normally and avoids a duplicate (wasteful) PIT run upstream.
# ---------------------------------------------------------------------------
# shellcheck disable=SC2086
"$MVN" -pl "$MODULE" -am install -DskipTests $EXTRA_ARGS

# shellcheck disable=SC2086
"$MVN" org.pitest:pitest-maven:mutationCoverage -pl "$MODULE" $EXTRA_ARGS
