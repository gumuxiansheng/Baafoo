#!/bin/bash
# =============================================================================
# Download external agent JARs for multi-agent coexistence tests.
#
# These JARs are NOT committed to git (*.jar is gitignored) and must be
# downloaded before running multi-agent tests (MULTI_AGENT_ENABLED=1).
#
# Usage:
#   bash testing/4_E2ETest/enterprise/spring-cloud-alibaba/agents/download-agents.sh
#
# Downloads:
#   1. Apache SkyWalking Java Agent v9.4.0 (~23MB)
#   2. JaCoCo Agent v0.8.11 (~300KB)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENTS_DIR="$SCRIPT_DIR"

SW_VERSION="9.4.0"
JACOCO_VERSION="0.8.11"

SW_TGZ="$AGENTS_DIR/skywalking-agent.tgz"
SW_DIR="$AGENTS_DIR/skywalking-agent"
SW_JAR="$SW_DIR/skywalking-agent.jar"

JACOCO_JAR="$AGENTS_DIR/jacoco-agent.jar"

# --- SkyWalking Java Agent ---
if [[ -f "$SW_JAR" ]]; then
    echo "[OK] SkyWalking agent already exists: $SW_JAR"
else
    echo "[STEP] Downloading Apache SkyWalking Java Agent v$SW_VERSION..."
    SW_URL="https://archive.apache.org/dist/skywalking/java-agent/${SW_VERSION}/apache-skywalking-java-agent-${SW_VERSION}.tgz"
    echo "  URL: $SW_URL"
    curl -fSL -o "$SW_TGZ" "$SW_URL"
    echo "  Downloaded $(du -h "$SW_TGZ" | cut -f1)"
    tar -xzf "$SW_TGZ" -C "$AGENTS_DIR"
    # The tarball extracts to a directory named "skywalking-agent"
    if [[ ! -f "$SW_JAR" ]]; then
        echo "[ERR] SkyWalking agent JAR not found after extraction: $SW_JAR"
        exit 1
    fi
    echo "[OK] SkyWalking agent extracted to: $SW_DIR"
fi

# --- JaCoCo Agent ---
if [[ -f "$JACOCO_JAR" ]]; then
    echo "[OK] JaCoCo agent already exists: $JACOCO_JAR"
else
    echo "[STEP] Downloading JaCoCo Agent v$JACOCO_VERSION..."
    # The JaCoCo agent JAR used with -javaagent: is "jacocoagent.jar"
    # inside the org.jacoco.agent Maven artifact.
    JACOCO_URL="https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/${JACOCO_VERSION}/org.jacoco.agent-${JACOCO_VERSION}.jar"
    echo "  URL: $JACOCO_URL"
    # Download the agent bundle JAR, then extract jacocoagent.jar from it.
    TMP_JAR="$AGENTS_DIR/.jacoco-tmp.jar"
    curl -fSL -o "$TMP_JAR" "$JACOCO_URL"
    # The bundle JAR contains jacocoagent.jar at its root
    unzip -o "$TMP_JAR" "jacocoagent.jar" -d "$AGENTS_DIR"
    mv "$AGENTS_DIR/jacocoagent.jar" "$JACOCO_JAR"
    rm -f "$TMP_JAR"
    if [[ ! -f "$JACOCO_JAR" ]]; then
        echo "[ERR] JaCoCo agent JAR not found after extraction: $JACOCO_JAR"
        exit 1
    fi
    echo "[OK] JaCoCo agent extracted to: $JACOCO_JAR"
fi

echo ""
echo "=== Agent JARs ready ==="
echo "  SkyWalking: $SW_JAR ($(du -h "$SW_JAR" | cut -f1))"
echo "  JaCoCo:    $JACOCO_JAR ($(du -h "$JACOCO_JAR" | cut -f1))"
