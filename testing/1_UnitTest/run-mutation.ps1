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

$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        mvn org.pitest:pitest-maven:mutationCoverage -pl $Module -am $ExtraArgs
    } elseif (Test-Path (Join-Path $projectRoot "mvnw")) {
        $mvnwPath = Join-Path $projectRoot "mvnw"
        Write-Host "Using Maven wrapper: $mvnwPath"
        & "$mvnwPath" org.pitest:pitest-maven:mutationCoverage -pl $Module -am $ExtraArgs
    } else {
        Write-Error "Neither mvn nor mvnw found."
    }
} finally {
    Pop-Location
}
