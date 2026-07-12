param(
    [Parameter(Position=0)]
    [string]$Module = "baafoo-core",
    [string]$ExtraArgs = "",
    [switch]$Help
)

if ($Help) {
    Write-Host @"
Usage: .\run-mutation.ps1 [-Module <module>] [-ExtraArgs <args>] [-Help]

Run mutation testing (PIT) with JDK 8.

Examples:
    .\run-mutation.ps1                                         # baafoo-core
    .\run-mutation.ps1 -Module baafoo-core
    .\run-mutation.ps1 -Module baafoo-server
    .\run-mutation.ps1 -Module baafoo-server -ExtraArgs "-Dpitest.mutationThreshold=15"

Prerequisites:
    1. Copy testing\1_UnitTest\toolchains.xml to ~/.m2\toolchains.xml (edit the JDK path)
    2. Or set JAVA_HOME to JDK 8 before running
"@
    return
}

$jdk8 = "C:\Program Files\Java\jdk1.8.0_202"
if (Test-Path $jdk8) {
    Write-Host "Using JDK 8 at: $jdk8"
    $env:JAVA_HOME = $jdk8
} else {
    Write-Host "JDK 8 not found at $jdk8. Using current JAVA_HOME ($env:JAVA_HOME)."
    Write-Host "Set JAVA_HOME to a JDK 8 installation before running."
}

# PROJECT_ROOT is the repository root — the directory holding the baafoo-parent
# aggregator pom.xml (the one that declares the <modules> including baafoo-core).
# This script lives at testing/1_UnitTest/, so the root is two levels up. Walk
# up defensively in case the script is relocated.
$projectRoot = $PSScriptRoot
while (-not (Test-Path (Join-Path $projectRoot "pom.xml")) -or
       -not ((Get-Content (Join-Path $projectRoot "pom.xml") -Raw) -match "baafoo-parent")) {
    $parent = Split-Path $projectRoot -Parent
    if ($parent -eq $projectRoot) {
        Write-Error "ERROR: could not locate the baafoo-parent pom.xml (repository root)."
        exit 1
    }
    $projectRoot = $parent
}
Push-Location $projectRoot
try {
    # Two-step build (required for inter-module deps like baafoo-plugin-api):
    # Step 1 installs the module + upstream deps (tests skipped; PIT runs them
    # in step 2) so their jars exist in the local Maven repo. Step 2 runs PIT
    # only on the target module so it can resolve those dependencies.
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        mvn -pl $Module -am install -DskipTests $ExtraArgs
        mvn org.pitest:pitest-maven:mutationCoverage -pl $Module $ExtraArgs
    } elseif (Test-Path (Join-Path $projectRoot "mvnw")) {
        $mvnwPath = Join-Path $projectRoot "mvnw"
        Write-Host "Using Maven wrapper: $mvnwPath"
        & "$mvnwPath" -pl $Module -am install -DskipTests $ExtraArgs
        & "$mvnwPath" org.pitest:pitest-maven:mutationCoverage -pl $Module $ExtraArgs
    } else {
        Write-Error "Neither mvn nor mvnw found."
    }
} finally {
    Pop-Location
}
