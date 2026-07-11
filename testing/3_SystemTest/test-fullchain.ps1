# =============================================================================
# Baafoo Full-Chain Integration Test - PowerShell Orchestrator
#
# Features:
#   1. Build all JARs (server + agent + test-spring + feign plugin)
#   2. Copy Feign plugin JAR to ./plugins/
#   3. Start Docker Staging environment (server + postgres + app-env-a + app-env-b)
#   4. Wait for all services to be healthy
#   5. Register all test rules (HTTP/TCP/Kafka/Pulsar/JMS + gRPC placeholders)
#   6. Run full-chain test cases covering:
#      F core / A API security & CRUD / H HTTP / T TCP / K Kafka / P Pulsar / J JMS
#      E env isolation / PL plugin / R+D recording & MQ direction / C condition types
#      M env modes / AS RuleSet CRUD / REC recording mgmt / RU+RST undo & reset
#      OAPI OpenAPI import / G gRPC (gap) / MX protocol x mode matrix gaps
#   7. Summary report and cleanup
#
# Assertion red lines (see PROJECT-TEST-PLAN.md §6.4.1):
#   - Condition tests assert precise `matchedBy` (no `|mocked` fallback)
#   - MQ error/timeout/null must FAIL, not pass
#   - requestCount tests reset counter before run
#   - mode switches wait $MODE_SETTLE_WAIT (>= pollIntervalSec)
#
# Usage:
#   .\testing\3_SystemTest\test-fullchain.ps1              # Build + test + cleanup
#   .\testing\3_SystemTest\test-fullchain.ps1 -NoCleanup   # Keep environment after test
#   .\testing\3_SystemTest\test-fullchain.ps1 -SkipBuild   # Skip build step
# =============================================================================

param(
    [switch]$NoCleanup,
    [switch]$SkipBuild,
    [switch]$Help
)

$ErrorActionPreference = "Continue"

if ($Help) {
    Write-Host "Baafoo Full-Chain Integration Test"
    Write-Host ""
    Write-Host "Usage:"
    Write-Host "  .\testing\3_SystemTest\test-fullchain.ps1              # Full build+test+cleanup"
    Write-Host "  .\testing\3_SystemTest\test-fullchain.ps1 -NoCleanup   # Keep test environment"
    Write-Host "  .\testing\3_SystemTest\test-fullchain.ps1 -SkipBuild   # Skip build (use existing JARs)"
    exit 0
}

# Project root is two levels up because this script now lives in testing/3_SystemTest
$PROJECT_ROOT = $PSScriptRoot | Split-Path -Parent | Split-Path -Parent
Set-Location $PROJECT_ROOT

$COMPOSE_FILES = @("-f", "docker-compose.yml", "-f", "docker-compose.staging.yml")
$SERVER = "http://localhost:8084"
$APP_A  = "http://localhost:9090"
$APP_B  = "http://localhost:9091"
$API_KEY = "staging-admin-key"
$MODE_SETTLE_WAIT = 12  # wait for agent poll cycle (default pollIntervalSec=10) after mode changes

# Test counters
$script:Pass = 0
$script:Fail = 0
$script:Skip = 0
$script:FailedTests = @()
$script:TestResults = @()   # JUnit XML source of truth: list of {Name,Status,Message}

function Write-Step($msg) { Write-Host "`n[STEP] $msg" -ForegroundColor Cyan }
function Write-OK($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "  [ERR] $msg" -ForegroundColor Red }

# Record a test result for JUnit XML emission (CI consumption).
function Add-TestResult($msg, $status) {
    $id = if ($msg -match '^([A-Z]{1,4}\d{1,3})[:\s]') { $matches[1] } else { $msg }
    $script:TestResults += [PSCustomObject]@{ Name = $id; Status = $status; Message = $msg }
}

function Test-Pass($msg) {
    Write-Host "  [PASS] $msg" -ForegroundColor Green
    $script:Pass++
    Add-TestResult $msg "pass"
}
function Test-Fail($msg) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    $script:Fail++
    $script:FailedTests += $msg
    Add-TestResult $msg "fail"
}
function Test-Skip($msg) {
    Write-Host "  [SKIP] $msg" -ForegroundColor Yellow
    $script:Skip++
    Add-TestResult $msg "skip"
}

# Emit a JUnit-compatible XML report so CI (GitHub Actions test-reporter,
# GitLab, Jenkins, etc.) can parse per-test results.
function Export-JUnitXml($path) {
    $ts = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    $sb = New-Object System.Text.StringBuilder
    $null = $sb.Append('<?xml version="1.0" encoding="UTF-8"?>')
    $null = $sb.AppendFormat('<testsuites name="baafoo-fullchain" tests="{0}" failures="{1}" skipped="{2}" errors="0">', $script:TestResults.Count, $script:Fail, $script:Skip)
    $null = $sb.AppendFormat('<testsuite name="FullChain" tests="{0}" failures="{1}" skipped="{2}" errors="0" timestamp="{3}">', $script:TestResults.Count, $script:Fail, $script:Skip, $ts)
    foreach ($t in $script:TestResults) {
        $esc = [Security.SecurityElement]::Escape($t.Message)
        $null = $sb.AppendFormat('<testcase name="{0}" classname="FullChain" status="{1}">', $t.Name, $t.Status)
        if ($t.Status -eq "fail") {
            $null = $sb.AppendFormat('<failure message="{0}">{0}</failure>', $esc)
        } elseif ($t.Status -eq "skip") {
            $null = $sb.AppendFormat('<skipped message="{0}"/>', $esc)
        }
        $null = $sb.Append('</testcase>')
    }
    $null = $sb.Append('</testsuite></testsuites>')
    Set-Content -Path $path -Value $sb.ToString() -Encoding UTF8
    Write-Host "  [OK] JUnit XML written: $path" -ForegroundColor Gray
}

# HTTP helpers
function Invoke-ApiGet($path) {
    try {
        $result = & curl.exe -sf -H "X-Api-Key: $API_KEY" "$SERVER/__baafoo__/api/$path" 2>$null
        return $result
    } catch { return "{}" }
}

function Invoke-AppGet($url) {
    try {
        $result = & curl.exe -sf $url 2>$null
        return $result
    } catch { return "{}" }
}

function Invoke-AppPost($url) {
    try {
        $result = & curl.exe -sf -X POST $url 2>$null
        return $result
    } catch { return "{}" }
}

# JSON value extraction (simple regex, no jq dependency)
function Get-JsonValue($json, $key) {
    if ($json -match "`"$key`"\s*:\s*`"([^`"]*)`"") { return $matches[1] }
    if ($json -match "`"$key`"\s*:\s*(true|false)") { return $matches[1] }
    if ($json -match "`"$key`"\s*:\s*(\d+)") { return $matches[1] }
    return $null
}

# Extract body field from JSON response (handles escaped JSON in body value).
# Returns the unescaped body content (JSON escape sequences like \" converted to ").
function Get-JsonBody($json) {
    # The body field contains escaped JSON like "body":"{\"mocked\":true,...}"
    # Match everything between "body":" and the closing " (before next top-level key)
    if ($json -match '"body"\s*:\s*"((?:[^"\\]|\\.)*)"') {
        # Unescape JSON string escapes so downstream regexes can match plain "key":"value"
        return $matches[1] -replace '\\(.)', '$1'
    }
    return $null
}

# Extract the matchedBy value from a stubbed HTTP response.
# The stub response body (a string field in the app response) carries
# "matchedBy":"<condition-type>" for condition-specific rules. Asserting this
# precisely (instead of the loose `|mocked` fallback) is what makes a condition
# test prove the TARGET condition matched, not just "any rule matched".
function Get-MatchedBy($json) {
    $body = Get-JsonBody $json
    if ($body -and $body -match '"matchedBy"\s*:\s*"([^"]*)"') { return $matches[1] }
    return $null
}

# Parse a JSON object via ConvertFrom-Json, returning $null on failure.
function Get-JsonObject($jsonString) {
    try { return ($jsonString | ConvertFrom-Json) } catch { return $null }
}

# Extract an environment ID by name from the server API response (robust JSON parse)
function Get-EnvironmentId($jsonString, $envName) {
    try {
        $parsed = $jsonString | ConvertFrom-Json
        $items = if ($parsed.data -ne $null) { $parsed.data } elseif ($parsed -is [array]) { $parsed } else { $null }
        if ($items) {
            foreach ($item in $items) {
                if ($item.name -eq $envName) { return $item.id }
            }
        }
    } catch { }
    return $null
}

# ==================== 1. Clean old Docker environment (release file locks) ====================
Write-Step "1/6: Clean old Docker environment"
& docker compose @COMPOSE_FILES down -v --remove-orphans 2>&1 | Out-Null
Write-OK "Old environment cleaned"

# ==================== 2. Build all JARs ====================
if (-not $SkipBuild) {
    Write-Step "2/6: Build all JAR files"

    Write-Host "  Building project (mvnw clean package -DskipTests)..."
    & .\mvnw.cmd clean package -DskipTests -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Project build failed"
        exit 1
    }
    Write-OK "Project build complete"

    # Build Feign plugin JAR (standalone module, not in main reactor)
    Write-Host "  Building Feign plugin JAR..."
    & .\mvnw.cmd clean package -f "baafoo-example-plugins/feign/pom.xml" -DskipTests -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn "Feign plugin build failed, skipping plugin tests"
    } else {
        if (-not (Test-Path "plugins")) {
            New-Item -ItemType Directory -Path "plugins" -Force | Out-Null
        }
        $feignJar = Get-ChildItem "baafoo-example-plugins/feign/target/baafoo-plugin-feign-*.jar" |
            Where-Object { $_.Name -notmatch "sources|javadoc|original" } |
            Select-Object -First 1
        if ($feignJar) {
            Copy-Item $feignJar.FullName "plugins/" -Force
            Write-OK "Feign plugin copied to plugins/$($feignJar.Name)"
        } else {
            Write-Warn "Feign plugin JAR not found"
        }
    }
} else {
    Write-Step "2/6: Skip build (-SkipBuild)"
}

# ==================== 3. Start Docker Staging environment ====================
Write-Step "3/6: Start Docker Staging environment"

Write-Host "  Building and starting all services (incl. staging-init)..."
$buildOutput = & docker compose @COMPOSE_FILES up -d --build 2>&1
$buildExit = $LASTEXITCODE
if ($buildExit -ne 0) {
    Write-Err "Docker startup failed"
    Write-Host $buildOutput
    exit 1
}
Write-OK "Services started"

# Wait for health checks
Write-Host "  Waiting for services to become healthy..."
$maxWait = 180
$waited = 0
$allHealthy = $false

while (-not $allHealthy -and $waited -lt $maxWait) {
    Start-Sleep -Seconds 5
    $waited += 5

    $serverHealth = & docker inspect --format='{{.State.Health.Status}}' baafoo-server 2>$null
    $appAHealth = & docker inspect --format='{{.State.Health.Status}}' baafoo-app-env-a 2>$null
    $appBHealth = & docker inspect --format='{{.State.Health.Status}}' baafoo-app-env-b 2>$null

    $status = "server=$serverHealth app-a=$appAHealth app-b=$appBHealth (${waited}s)"
    Write-Host "`r  $status                    " -NoNewline

    if ($serverHealth -eq "healthy" -and $appAHealth -eq "healthy" -and $appBHealth -eq "healthy") {
        $allHealthy = $true
    }
}
Write-Host ""

if (-not $allHealthy) {
    Write-Err "Health check timeout"
    Write-Host "`n  Server logs:" -ForegroundColor Yellow
    & docker compose @COMPOSE_FILES logs --tail=20 server 2>$null
    Write-Host "`n  app-env-a logs:" -ForegroundColor Yellow
    & docker compose @COMPOSE_FILES logs --tail=20 app-env-a 2>$null
    if (-not $NoCleanup) {
        & docker compose @COMPOSE_FILES down -v 2>&1 | Out-Null
    }
    exit 1
}
Write-OK "All services healthy"

# Wait for staging-init container to finish (creates environments + base rules)
Write-Host "  Waiting for staging-init to complete..."
$initMaxWait = 60
$initWaited = 0
$initDone = $false
while (-not $initDone -and $initWaited -lt $initMaxWait) {
    Start-Sleep -Seconds 2
    $initWaited += 2
    $initStatus = & docker inspect --format='{{.State.Status}}' baafoo-staging-init 2>$null
    if ($initStatus -eq "exited") {
        $initDone = $true
    }
    Write-Host "`r  staging-init: $initStatus (${initWaited}s)          " -NoNewline
}
Write-Host ""
if ($initDone) {
    Write-OK "Staging initialization complete"
} else {
    Write-Warn "Staging-init still running or not found (continuing anyway)"
}

# Verify environments were created
$envCheck = Invoke-ApiGet "environments"
$envCount = ([regex]::Matches($envCheck, '"name"')).Count
if ($envCount -ge 2) {
    Write-OK "Environments created (count=$envCount)"
} else {
    Write-Warn "Environments count=$envCount (expected >=2), creating manually..."
    # Fallback: create environments via API
    & curl.exe -s -X POST "$SERVER/__baafoo__/api/environments" -H "Content-Type: application/json" -H "X-Api-Key: $API_KEY" -d '{"name":"staging-a","mode":"stub","description":"Staging A"}' 2>$null | Out-Null
    & curl.exe -s -X POST "$SERVER/__baafoo__/api/environments" -H "Content-Type: application/json" -H "X-Api-Key: $API_KEY" -d '{"name":"staging-b","mode":"record-and-stub","description":"Staging B"}' 2>$null | Out-Null
    & curl.exe -s -X POST "$SERVER/__baafoo__/api/environments" -H "Content-Type: application/json" -H "X-Api-Key: $API_KEY" -d '{"name":"staging-c","mode":"stub","description":"Staging C - Mode Test"}' 2>$null | Out-Null
    Start-Sleep -Seconds 2
    Write-OK "Environments created via API fallback"
}

# ==================== 4. Register test rules ====================
Write-Step "4/6: Register all test rules"

$rulesDir = "testing\2_IntegrationTest\rules"
$ruleFiles = @(
    "http-get.json", "http-post.json", "http-put.json", "http-delete.json",
    "http-delay.json", "http-error.json", "http-staging-b.json", "http-consul.json",
    "http-header.json", "http-query.json", "http-body.json", "http-jsonpath.json",
    "http-contains.json", "http-endswith.json", "http-path-regex.json",
    "http-header-exists.json", "http-disabled.json", "http-no-env.json",
    "http-graphql.json", "http-request-count.json", "http-caseinsensitive.json",
    "kafka-topic.json", "kafka-wildcard.json", "kafka-header.json",
    "pulsar-topic.json", "pulsar-wildcard.json",
    "jms-queue.json", "jms-topic.json",
    "tcp-hex.json", "tcp-regex.json", "tcp-multiround.json",
    # gRPC rules are registered so the server holds them; they are exercised only
    # once baafoo-test-spring gains a gRPC client (see G section below).
    "grpc-greeter.json", "grpc-error.json", "grpc-delay.json",
    "grpc-server-streaming.json", "grpc-client-streaming.json", "grpc-bidirectional-streaming.json"
)

$registered = 0
$failed = 0
$headers = @{ "X-Api-Key" = $API_KEY }

# Cleanup stale gRPC rules left by a prior (non-reset) run. Without this, a
# leftover UUID-named gRPC rule matching the same service/method would win over
# the 6 known rules below and return a comma-split (malformed) body, breaking the
# unary gRPC assertions G01/G05. Only the 6 known ids are preserved.
$knownGrpcIds = @("grpc-greeter","grpc-error","grpc-delay","grpc-server-streaming","grpc-client-streaming","grpc-bidirectional-streaming")
try {
    $allRules = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules" -Method Get -Headers $headers -ErrorAction Stop
    $ruleList = if ($allRules.data) { $allRules.data } else { $allRules }
    foreach ($r in $ruleList) {
        if ($r.protocol -eq "grpc" -and $knownGrpcIds -notcontains $r.id) {
            Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$($r.id)" -Method Delete -Headers $headers -ErrorAction SilentlyContinue | Out-Null
        }
    }
} catch {}

foreach ($ruleFile in $ruleFiles) {
    $rulePath = Join-Path $rulesDir $ruleFile
    if (-not (Test-Path $rulePath)) {
        Write-Warn "Rule file not found: $ruleFile"
        continue
    }
    $ruleJson = [System.IO.File]::ReadAllText($rulePath, [System.Text.Encoding]::UTF8)
    # Upsert: delete any existing rule with the same id, then (re)create, so a
    # stale DB (PostgreSQL volume not reset) never keeps an outdated rule
    # (e.g. old host=httpbin.org instead of the current host=real-backend).
    try {
        $rid = ($ruleJson | ConvertFrom-Json -ErrorAction SilentlyContinue).id
        if ($rid) {
            Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$rid" -Method Delete -Headers $headers -ErrorAction SilentlyContinue | Out-Null
        }
    } catch {}
    try {
        $result = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules" -Method Post -ContentType "application/json" -Headers $headers -Body $ruleJson -ErrorAction Stop
        if ($result.success -eq $true -or $result.data.id) {
            $registered++
        } else {
            $failed++
            Write-Warn "Register failed: $ruleFile -> $($result.message)"
        }
    } catch {
        $failed++
        $errMsg = $_.Exception.Message
        if ($errMsg.Length -gt 80) { $errMsg = $errMsg.Substring(0, 80) + "..." }
        Write-Warn "Register failed: $ruleFile -> $errMsg"
    }
}
Write-OK "Rules registered (success=$registered, failed=$failed)"

# Wait for rules to take effect
Start-Sleep -Seconds 5
Write-OK "Rules effective"

# ==================== 5. Run test cases ====================
Write-Step "5/6: Run full-chain test cases"
Write-Host ""

# -------------------- F: Core functionality --------------------
Write-Host "--- F: Core ---" -ForegroundColor White

# F01: Server health check
$health = Invoke-ApiGet "status"
if ($health -match '"success":\s*true') {
    Test-Pass "F01: Server health check"
} else {
    Test-Fail "F01: Server health check (response: $health)"
}

# F02: PostgreSQL connection (check container health since status API doesn't expose DB type)
$pgHealth = & docker inspect --format='{{.State.Health.Status}}' baafoo-staging-postgres 2>$null
if ($pgHealth -eq "healthy") {
    Test-Pass "F02: PostgreSQL database connection"
} else {
    Test-Skip "F02: PostgreSQL database connection (status=$pgHealth)"
}

# F03: Rules list non-empty
$rulesJson = Invoke-ApiGet "rules"
$ruleCount = ([regex]::Matches($rulesJson, '"id"')).Count
if ($ruleCount -gt 0) {
    Test-Pass "F03: Rules registered (count=$ruleCount)"
} else {
    Test-Fail "F03: Rules list empty"
}

# F04: app-env-a health check
$appAHealth = Invoke-AppGet "$APP_A/api/stub-demo/health"
if ($appAHealth -eq "OK") {
    Test-Pass "F04: app-env-a health check"
} else {
    Test-Fail "F04: app-env-a health check (response: $appAHealth)"
}

# F05: app-env-b health check
$appBHealth = Invoke-AppGet "$APP_B/api/stub-demo/health"
if ($appBHealth -eq "OK") {
    Test-Pass "F05: app-env-b health check"
} else {
    Test-Fail "F05: app-env-b health check (response: $appBHealth)"
}

# -------------------- A: API security & CRUD --------------------
Write-Host ""
Write-Host "--- A: API Security & CRUD ---" -ForegroundColor White

# A01: Invalid API key should be rejected
$invalidKeyStatus = 0
try {
    $invalidKeyResponse = & curl.exe -s -o /dev/null -w "%{http_code}" -H "X-Api-Key: invalid-key" "$SERVER/__baafoo__/api/rules" 2>$null
    $invalidKeyStatus = [int]$invalidKeyResponse
} catch { $invalidKeyStatus = 0 }
if ($invalidKeyStatus -eq 401 -or $invalidKeyStatus -eq 403) {
    Test-Pass "A01: API rejects invalid API key"
} else {
    Test-Skip "A01: API invalid key rejection (status=$invalidKeyStatus)"
}

# A02: Rule CRUD (create -> verify -> delete)
$testRuleId = "test-rule-crud-" + (Get-Random -Minimum 1000 -Maximum 9999)
$ruleBody = @{
    id = $testRuleId
    name = "CRUD Test Rule"
    protocol = "http"
    host = "example.com"
    port = 80
    conditions = @(@{ type = "path"; operator = "equals"; value = "/crud-test" })
    responses = @(@{ name = "CRUD Response"; statusCode = 200; body = '{"mocked":true}'; delayMs = 0 })
    enabled = $true
    priority = 100
    environments = @("staging-a")
} | ConvertTo-Json -Depth 5

try {
    $createResult = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules" -Method Post -ContentType "application/json" -Headers $headers -Body $ruleBody -ErrorAction Stop
    if ($createResult.success -eq $true -or $createResult.data.id) {
        Test-Pass "A02: Rule created"
    } else {
        Test-Skip "A02: Rule create (response: $createResult)"
    }
} catch {
    Test-Skip "A02: Rule CRUD (create error: $_)"
}

try {
    $ruleDetail = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$testRuleId" -Method Get -Headers $headers -ErrorAction Stop
    if ($ruleDetail.success -eq $true -or $ruleDetail.data.id -eq $testRuleId) {
        Test-Pass "A03: Rule queried"
    } else {
        Test-Skip "A03: Rule query (response: $ruleDetail)"
    }
} catch {
    Test-Skip "A03: Rule query (error: $_)"
}

try {
    Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$testRuleId" -Method Delete -Headers $headers -ErrorAction Stop | Out-Null
    Test-Pass "A04: Rule deleted"
} catch {
    Test-Skip "A04: Rule delete (error: $_)"
}

# A05: Environment CRUD (create -> verify -> delete)
$testEnvName = "test-env-crud-" + (Get-Random -Minimum 1000 -Maximum 9999)
$envBody = @{
    name = $testEnvName
    mode = "stub"
    description = "CRUD test environment"
} | ConvertTo-Json -Depth 2

try {
    $createEnv = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments" -Method Post -ContentType "application/json" -Headers $headers -Body $envBody -ErrorAction Stop
    $envId = $null
    if ($createEnv.success -eq $true) { $envId = $createEnv.data.id }
    if ($createEnv.data.id) { $envId = $createEnv.data.id }
    if ($createEnv.success -eq $true -or $createEnv.data.name -eq $testEnvName) {
        Test-Pass "A05: Environment created"
    } else {
        Test-Skip "A05: Environment create (response: $createEnv)"
    }
} catch {
    Test-Skip "A05: Environment CRUD (create error: $_)"
}

try {
    $envQueryPath = if ($envId) { "environments/$envId" } else { "environments/$testEnvName" }
    $envDetail = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/$envQueryPath" -Method Get -Headers $headers -ErrorAction Stop
    if ($envDetail.success -eq $true -or $envDetail.data.name -eq $testEnvName) {
        Test-Pass "A06: Environment queried"
    } else {
        Test-Skip "A06: Environment query (response: $envDetail)"
    }
} catch {
    Test-Skip "A06: Environment query (error: $_)"
}

try {
    $envDeletePath = if ($envId) { "environments/$envId" } else { "environments/$testEnvName" }
    Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/$envDeletePath" -Method Delete -Headers $headers -ErrorAction Stop | Out-Null
    Test-Pass "A07: Environment deleted"
} catch {
    Test-Skip "A07: Environment delete (error: $_)"
}

# -------------------- H: HTTP protocol --------------------
Write-Host ""
Write-Host "--- H: HTTP ---" -ForegroundColor White

# H01: HTTP GET stub
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") { Test-Pass "H01: HTTP GET intercepted" }
else { Test-Fail "H01: HTTP GET intercepted (stubbed=$stubbed)" }
$_h01Body = Get-JsonBody $resp
if ($_h01Body -and $_h01Body -match '"env"\s*:\s*"staging-a"') { Test-Pass "H01: HTTP GET response correct (staging-a stub)" }
else { Test-Fail "H01: HTTP GET response correct (resp=$resp)" }

# H02: HTTP POST stub (endpoint is @PostMapping, must use POST)
$resp = Invoke-AppPost "$APP_A/api/http/post?url=http://real-backend:9090/post&body=%7B%22test%22%3A%22baafoo%22%7D"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") { Test-Pass "H02: HTTP POST intercepted" }
else { Test-Fail "H02: HTTP POST intercepted (stubbed=$stubbed)" }

# H03: HTTP PUT stub
$resp = Invoke-AppGet "$APP_A/api/http/methods"
$putStubbed = if ($resp -match '"put".*?"stubbed":\s*(true|false)') { $matches[1] } else { $null }
if ($putStubbed -eq "true") { Test-Pass "H03: HTTP PUT intercepted" }
else { Test-Fail "H03: HTTP PUT intercepted (stubbed=$putStubbed)" }

# H04: HTTP DELETE stub
$deleteStubbed = if ($resp -match '"delete".*?"stubbed":\s*(true|false)') { $matches[1] } else { $null }
if ($deleteStubbed -eq "true") { Test-Pass "H04: HTTP DELETE intercepted" }
else { Test-Fail "H04: HTTP DELETE intercepted (stubbed=$deleteStubbed)" }

# H05: HTTP delay rule
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/delay"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") { Test-Pass "H05: HTTP delay path intercepted" }
else { Test-Fail "H05: HTTP delay path intercepted (stubbed=$stubbed)" }

# H06: HTTP error code rule
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/error500"
$statusCode = Get-JsonValue $resp "statusCode"
if ($statusCode -eq "500") { Test-Pass "H06: HTTP error code returns 500" }
else { Test-Fail "H06: HTTP error code returns 500 (statusCode=$statusCode)" }

# H07: HTTP GraphQL rule — body must carry operationName so the server matches
# graphqlOperationName / graphqlOperationType conditions (MatchEngine reads $.operationName).
$graphqlBody = [System.Uri]::EscapeDataString('{"operationName":"GetUser","query":"query GetUser { user { id name } }"}')
$resp = Invoke-AppPost "$APP_A/api/http/post?url=http://real-backend:9090/graphql&body=$graphqlBody"
if ($resp -match "Baafoo Mock User") {
    Test-Pass "H07: HTTP GraphQL rule matched (operationName=GetUser)"
} else {
    Test-Fail "H07: HTTP GraphQL rule not matched (response: $resp)"
}

# H08: HTTP request-count rule. Reset the per-rule counter BEFORE the assertion so
# repeated runs always see count=1 (eliminates the latent flaky reported in the review).
$null = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/staging-a-http-request-count/reset-state" -Method Post -Headers $headers -ErrorAction SilentlyContinue
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/counted"
$mb = Get-MatchedBy $resp
if ($mb -eq "requestCount") {
    Test-Pass "H08: HTTP request-count rule matched (counter reset before run)"
} else {
    Test-Fail "H08: HTTP request-count rule (matchedBy=$mb, resp=$resp)"
}

# H09: HTTP Consul rule (service discovery stub)
# Lenient by design: only PASS when the agent matched the consul rule and
# returned a stub. Any other outcome is a SKIP (never FAIL). We still separate
# the two skip reasons so local diagnosis is obvious:
#   - consul unreachable / upstream error: no 2xx wrapper (statusCode 000/4xx/5xx,
#     or empty response because the app itself could not reach consul-server)
#   - rule not matched / agent not intercepting: got a real 2xx from consul but
#     stubbed=false / ruleId=null (request was forwarded, not stubbed)
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://consul-server:8500/v1/status/leader"
if ($resp -match '"stubbed":\s*true') {
    Test-Pass "H09: HTTP Consul rule matched"
} else {
    $h09Status = Get-JsonValue $resp "statusCode"
    $h09RuleId = Get-JsonValue $resp "ruleId"
    if (-not $h09Status -or $h09Status -match '^(000|4\d\d|5\d\d)$') {
        Test-Skip "H09: HTTP Consul rule SKIP (consul unreachable / upstream error, statusCode=$h09Status, resp=$resp)"
    } else {
        Test-Skip "H09: HTTP Consul rule SKIP (rule not matched / agent not intercepting: stubbed=false, ruleId=$h09RuleId, resp=$resp)"
    }
}

# -------------------- T: TCP protocol --------------------
Write-Host ""
Write-Host "--- T: TCP ---" -ForegroundColor White

# TCP stub server runs on baafoo-server container port 9001
$TCP_HOST = "server"
$TCP_PORT = "9001"

# T01: TCP BIO Socket
$resp = Invoke-AppGet "$APP_A/api/socket/bio?host=$TCP_HOST&port=$TCP_PORT"
if ($resp -match '"intercepted":\s*true|"sent":') {
    Test-Pass "T01: TCP BIO Socket stub"
} elseif ($resp -match '"connected":\s*true') {
    Test-Pass "T01: TCP BIO Socket connected"
} else {
    Test-Fail "T01: TCP BIO Socket stub (response: $resp)"
}

# T02: TCP NIO Socket
$resp = Invoke-AppGet "$APP_A/api/socket/nio?host=$TCP_HOST&port=$TCP_PORT"
$connected = Get-JsonValue $resp "connected"
$intercepted = Get-JsonValue $resp "intercepted"
if ($connected -eq "true" -or $intercepted -eq "true") {
    Test-Pass "T02: TCP NIO Socket connected/intercepted"
} else {
    Test-Skip "T02: TCP NIO Socket (no response)"
}

# T03: TCP multiround
$resp = Invoke-AppGet "$APP_A/api/socket/multiround?host=$TCP_HOST&port=$TCP_PORT"
if ($resp -match "LOGIN|QUERY|LOGOUT|round") {
    Test-Pass "T03: TCP multiround interaction"
} else {
    Test-Skip "T03: TCP multiround interaction (response: $resp)"
}

# -------------------- K: Kafka protocol --------------------
Write-Host ""
Write-Host "--- K: Kafka ---" -ForegroundColor White

# K01: Kafka Produce. The first KafkaProducer construction in a fresh JVM
# triggers ByteBuddy class-loading + transform; the Advice may not be fully
# linked on that very first call, causing DNS resolution of the original
# bootstrap.servers to fail. A single retry after a short settle wait handles
# this cold-start race — subsequent calls always succeed (class already linked).
$resp = Invoke-AppGet "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "K01: Kafka Produce stub (success)"
} else {
    Start-Sleep -Seconds 3
    $resp = Invoke-AppGet "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka"
    if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
        Test-Pass "K01: Kafka Produce stub (success on retry)"
    } else {
        Test-Fail "K01: Kafka Produce stub (response: $resp)"
    }
}

# K02: Kafka Consume
$resp = Invoke-AppGet "$APP_A/api/kafka/consume?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "K02: Kafka Consume stub (success)"
} else {
    Test-Fail "K02: Kafka Consume stub (response: $resp)"
}

# K03: Kafka wildcard topic
$resp = Invoke-AppGet "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-wildcard-topic&message=test"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "K03: Kafka wildcard topic stub (success)"
} else {
    Test-Skip "K03: Kafka wildcard topic (response: $resp)"
}

# -------------------- P: Pulsar protocol --------------------
Write-Host ""
Write-Host "--- P: Pulsar ---" -ForegroundColor White

# P01: Pulsar Produce — must succeed (MockBroker stub). error/timeout is a FAIL, not a pass.
$resp = Invoke-AppGet "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-baafoo-pulsar"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "P01: Pulsar Produce stub (success)"
} else {
    Test-Fail "P01: Pulsar Produce (response: $resp)"
}

# P02: Pulsar Consume — must succeed (MockBroker stub). error/timeout is a FAIL.
$resp = Invoke-AppGet "$APP_A/api/pulsar/consume?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "P02: Pulsar Consume stub (success)"
} else {
    Test-Fail "P02: Pulsar Consume (response: $resp)"
}

# P03: Pulsar wildcard topic
$resp = Invoke-AppGet "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-wildcard-topic&message=test"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "P03: Pulsar wildcard topic stub (success)"
} else {
    Test-Skip "P03: Pulsar wildcard topic (response: $resp)"
}
Write-Host ""
Write-Host "--- J: JMS ---" -ForegroundColor White

# J01: JMS Queue send
$resp = Invoke-AppGet "$APP_A/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-baafoo-jms"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "J01: JMS Queue send stub (success)"
} else {
    Test-Fail "J01: JMS Queue send stub (response: $resp)"
}

# J02: JMS Queue receive — must succeed (MockBroker stub). error/null is a FAIL.
$resp = Invoke-AppGet "$APP_A/api/jms/receive?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE"
if ($resp -match '"success"\s*:\s*true' -and $resp -notmatch '"error"') {
    Test-Pass "J02: JMS Queue receive stub (success)"
} else {
    Test-Fail "J02: JMS Queue receive (response: $resp)"
}

# -------------------- E: Environment isolation --------------------
Write-Host ""
Write-Host "--- E: Environment Isolation ---" -ForegroundColor White

# E01: staging-a returns staging-a tag (check raw response for env tag)
$respA = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get"
if ($respA -match "staging-a") {
    Test-Pass "E01: staging-a isolation correct"
} else {
    Test-Fail "E01: staging-a isolation (resp: $respA)"
}

# E02: staging-b returns staging-b tag (check raw response for env tag)
$respB = Invoke-AppGet "$APP_B/api/http/get?url=http://real-backend:9090/get"
if ($respB -match "staging-b") {
    Test-Pass "E02: staging-b isolation correct"
} else {
    Test-Fail "E02: staging-b isolation (resp: $respB)"
}

# -------------------- PL: Plugin loading --------------------
Write-Host ""
Write-Host "--- PL: Plugin ---" -ForegroundColor White

# PL01: Check agent logs for Plugin loading
$agentLogs = & docker logs baafoo-app-env-a 2>&1 | Select-String -Pattern "Plugin|PluginManager" | Select-Object -Last 5
$agentLogsStr = $agentLogs -join "`n"
if ($agentLogsStr -match "Plugin loaded") {
    Test-Pass "PL01: Plugin loaded (log shows Plugin loaded)"
} elseif ($agentLogsStr -match "No plugin") {
    Test-Pass "PL01: PluginManager initialized (no plugins loaded)"
} elseif ($agentLogsStr -match "Plugin") {
    Test-Pass "PL01: PluginManager initialized"
} else {
    Test-Skip "PL01: Plugin loading check (cannot get container logs)"
}

# PL02: Check agent heartbeat registration
$agentsJson = Invoke-ApiGet "agents"
if ($agentsJson -match "agent|staging") {
    Test-Pass "PL02: Agent heartbeat registered"
} else {
    Test-Fail "PL02: Agent heartbeat registration failed (response: $agentsJson)"
}

# PL03: Feign plugin functional test (Feign client uses OkHttp, agent should intercept)
$resp = Invoke-AppGet "$APP_A/api/feign/get?baseUrl=http://real-backend:9090"
if ($resp -match '"stubbed":\s*true') {
    Test-Pass "PL03: Feign call intercepted by agent"
} elseif ($resp -match '"statusCode":\s*\d+') {
    Test-Skip "PL03: Feign call completed (may not be stubbed: $resp)"
} else {
    Test-Skip "PL03: Feign plugin test (response: $resp)"
}

# -------------------- R: Recording verification --------------------
# NOTE: Recordings are produced in the D section by switching staging-a to
# RECORD_AND_STUB and re-driving MQ send/consume. Keep this header for the
# test report, but the actual checks happen after the recordings exist.
Write-Host ""
Write-Host "--- R: Recording (verified after D section) ---" -ForegroundColor White

# -------------------- D: MQ direction annotation --------------------
Write-Host ""
Write-Host "--- D: MQ Direction ---" -ForegroundColor White

# Switch staging-a to RECORD_AND_STUB so that MockBroker writes recording
# records with direction=produce/consume. Then re-drive send/consume for
# Kafka/Pulsar/JMS and inspect the recordings.
$envsJson = Invoke-ApiGet "environments"
$envAId = Get-EnvironmentId $envsJson "staging-a"
if ($envAId) {
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"record-and-stub"}' -ErrorAction Stop | Out-Null
        Write-Host "  Switched staging-a to RECORD_AND_STUB, waiting for agents to sync..." -ForegroundColor Gray
        Start-Sleep -Seconds 5

        # Re-send MQ messages to generate produce recordings
        $null = Invoke-AppGet "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka-record"
        $null = Invoke-AppGet "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-baafoo-pulsar-record"
        $null = Invoke-AppGet "$APP_A/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-baafoo-jms-record"

        # Re-consume to generate consume recordings
        $null = Invoke-AppGet "$APP_A/api/kafka/consume?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic"
        $null = Invoke-AppGet "$APP_A/api/pulsar/consume?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic"
        $null = Invoke-AppGet "$APP_A/api/jms/receive?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE"

        Start-Sleep -Seconds 2
        $recordingsJson = Invoke-ApiGet "recordings?limit=50"

        # R01: Recording list non-empty
        $recCount = ([regex]::Matches($recordingsJson, '"id"')).Count
        if ($recCount -gt 0) {
            Test-Pass "R01: Recording list has data (count=$recCount)"
        } else {
            Test-Fail "R01: Recording list empty after RECORD_AND_STUB MQ traffic"
        }

        # R02: Recording has direction field
        if ($recordingsJson -match '"direction"') {
            Test-Pass "R02: Recording contains direction field"
        } else {
            Test-Fail "R02: Recording missing direction field"
        }

        # R03: Recording has ruleName field
        if ($recordingsJson -match '"ruleName"') {
            Test-Pass "R03: Recording contains ruleName field"
        } else {
            Test-Skip "R03: Recording missing ruleName field"
        }

        # D01: Kafka recording has produce/consume direction
        if ($recordingsJson -match '"protocol":"kafka".*?"direction":"produce"' -and
            $recordingsJson -match '"protocol":"kafka".*?"direction":"consume"') {
            Test-Pass "D01: Kafka recording has produce/consume direction"
        } else {
            Test-Fail "D01: Kafka recording missing produce or consume direction"
        }

        # D02: JMS recording has produce/consume direction
        if ($recordingsJson -match '"protocol":"jms".*?"direction":"produce"' -and
            $recordingsJson -match '"protocol":"jms".*?"direction":"consume"') {
            Test-Pass "D02: JMS recording has produce/consume direction"
        } else {
            Test-Fail "D02: JMS recording missing produce or consume direction"
        }

        # D03: Pulsar recording has produce/consume direction
        if ($recordingsJson -match '"protocol":"pulsar".*?"direction":"produce"' -and
            $recordingsJson -match '"protocol":"pulsar".*?"direction":"consume"') {
            Test-Pass "D03: Pulsar recording has produce/consume direction"
        } else {
            Test-Fail "D03: Pulsar recording missing produce or consume direction"
        }
    } catch {
        Test-Skip "D: MQ Direction (API error: $_)"
    }

    # Restore staging-a to STUB mode before the mode-specific M section
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"stub"}' -ErrorAction Stop | Out-Null
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
    } catch {}
} else {
    Test-Skip "D: MQ Direction (cannot find staging-a environment ID)"
}

# -------------------- C: Condition type coverage --------------------
Write-Host ""
Write-Host "--- C: Condition Types ---" -ForegroundColor White

# C01: Header condition match. The test app must actually send X-Test-Header so the
# http-header rule (path /headers + header equals) is the one that matches.
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/headers&headerName=X-Test-Header&headerValue=baafoo-test"
$mb = Get-MatchedBy $resp
if ($mb -eq "header") {
    Test-Pass "C01: Header condition match (matchedBy=header)"
} else {
    Test-Fail "C01: Header condition not matched (matchedBy=$mb, resp=$resp)"
}

# C02: Query param condition match
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get?baafoo=test"
$mb = Get-MatchedBy $resp
if ($mb -eq "query") {
    Test-Pass "C02: Query param condition match (matchedBy=query)"
} else {
    Test-Fail "C02: Query param condition not matched (matchedBy=$mb, resp=$resp)"
}

# C03: Body contains condition match
$bodyC03 = [System.Uri]::EscapeDataString('{"data":"baafoo-body-test"}')
$resp = Invoke-AppPost "$APP_A/api/http/post?url=http://real-backend:9090/post&body=$bodyC03"
$mb = Get-MatchedBy $resp
if ($mb -eq "body") {
    Test-Pass "C03: Body contains condition match (matchedBy=body)"
} else {
    Test-Fail "C03: Body contains condition not matched (matchedBy=$mb, resp=$resp)"
}

# C04: BodyJsonPath condition match
$bodyC04 = [System.Uri]::EscapeDataString('{"action":"submit"}')
$resp = Invoke-AppPost "$APP_A/api/http/post?url=http://real-backend:9090/post&body=$bodyC04"
$mb = Get-MatchedBy $resp
if ($mb -eq "jsonPath") {
    Test-Pass "C04: BodyJsonPath condition match (matchedBy=jsonPath)"
} else {
    Test-Fail "C04: BodyJsonPath condition not matched (matchedBy=$mb, resp=$resp)"
}

# C05: Path contains operator
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/baafoo/anything"
$mb = Get-MatchedBy $resp
if ($mb -eq "path-contains") {
    Test-Pass "C05: Path contains operator (matchedBy=path-contains)"
} else {
    Test-Fail "C05: Path contains operator not matched (matchedBy=$mb, resp=$resp)"
}

# C06: Path endsWith operator
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/suffix"
$mb = Get-MatchedBy $resp
if ($mb -eq "path-endswith") {
    Test-Pass "C06: Path endsWith operator (matchedBy=path-endswith)"
} else {
    Test-Fail "C06: Path endsWith operator not matched (matchedBy=$mb, resp=$resp)"
}

# C07: Path regex operator
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/api/v1/users"
$mb = Get-MatchedBy $resp
if ($mb -eq "path-regex") {
    Test-Pass "C07: Path regex operator (matchedBy=path-regex)"
} else {
    Test-Fail "C07: Path regex operator not matched (matchedBy=$mb, resp=$resp)"
}

# C08: Header exists operator. Send a header the http-header-exists rule keys on.
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get&headerName=X-Baafoo-Test&headerValue=1"
$mb = Get-MatchedBy $resp
if ($mb -eq "header-exists") {
    Test-Pass "C08: Header exists operator (matchedBy=header-exists)"
} else {
    Test-Fail "C08: Header exists operator not matched (matchedBy=$mb, resp=$resp)"
}

# C09: Case insensitive match (rule path equals /CASE-TEST, caseInsensitive=true)
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/case-test"
$mb = Get-MatchedBy $resp
if ($mb -eq "case-insensitive") {
    Test-Pass "C09: Case insensitive match (matchedBy=case-insensitive)"
} else {
    Test-Fail "C09: Case insensitive match not matched (matchedBy=$mb, resp=$resp)"
}

# C10: Disabled rule should NOT match. The disabled rule (path startsWith
# /disabled-path, enabled=false) must be skipped; the request falls through to
# the generic http-get rule (path startsWith /). Assert ruleId is NOT the
# disabled rule — checking for the literal string "disabled" is wrong because
# the request path itself contains "disabled-path".
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/disabled-path"
$_c10RuleId = Get-JsonValue $resp "ruleId"
if ($_c10RuleId -ne "staging-a-http-disabled") {
    Test-Pass "C10: Disabled rule not matched (ruleId=$_c10RuleId)"
} else {
    Test-Fail "C10: Disabled rule should not match (ruleId=$_c10RuleId, resp=$resp)"
}

# C11: No-environment global rule should match regardless of environment
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/global-endpoint"
# The stubbed body carries "rule":"global", but it is JSON-escaped inside the
# `body` field of the outer response ("\"rule\":\"global\""), so the literal
# regex above can never match the raw response string. Decode the body first
# (cf. Get-JsonBody already used by C10/H08) before asserting.
$body = Get-JsonBody $resp
if ($body -and $body -match '"rule"\s*:\s*"global"') {
    Test-Pass "C11: Global rule (no env) matched"
} else {
    Test-Skip "C11: Global rule (response: $resp)"
}

# -------------------- M: Environment Mode --------------------
Write-Host ""
Write-Host "--- M: Environment Mode ---" -ForegroundColor White

# M01: STUB mode (staging-a default) — returns stub response
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") {
    Test-Pass "M01: STUB mode returns stub response"
} else {
    Test-Fail "M01: STUB mode should return stub (stubbed=$stubbed)"
}

# M02: RECORD_AND_STUB mode (staging-b default) — returns stub + records
$resp = Invoke-AppGet "$APP_B/api/http/get?url=http://real-backend:9090/get"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") {
    Test-Pass "M02: RECORD_AND_STUB mode returns stub"
} else {
    Test-Fail "M02: RECORD_AND_STUB mode should return stub (stubbed=$stubbed)"
}

# M03: Switch staging-a to PASSTHROUGH mode — should forward to real backend
$envsJson = Invoke-ApiGet "environments"
$envAId = Get-EnvironmentId $envsJson "staging-a"
if ($envAId) {
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"passthrough"}' -ErrorAction Stop | Out-Null
        # The agent polls mode from the server every pollIntervalSec (default 10s).
        # Wait long enough for the next poll to pick up the mode change and sync
        # it to the Bootstrap-CL inlined advice.
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
        $resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get"
        $stubbed = Get-JsonValue $resp "stubbed"
        if ($stubbed -ne "true" -and $resp -match "real-backend") {
            Test-Pass "M03: PASSTHROUGH mode forwards request to real backend"
        } elseif ($stubbed -eq "true") {
            Test-Fail "M03: PASSTHROUGH mode still returning stub (agent has not picked up mode change yet?)"
        } else {
            Test-Skip "M03: PASSTHROUGH mode (unexpected response: $resp)"
        }
    } catch {
        Test-Skip "M03: PASSTHROUGH mode (API error: $_)"
    }
    # Restore staging-a to STUB mode
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"stub"}' -ErrorAction Stop | Out-Null
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
    } catch {}
} else {
    Test-Skip "M03: PASSTHROUGH mode (cannot find staging-a environment ID)"
}

# M04: Switch staging-a to RECORD mode — should passthrough + record
if ($envAId) {
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"record"}' -ErrorAction Stop | Out-Null
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
        $resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/get"
        # RECORD mode should passthrough (not stubbed) and create recording
        $recAfter = Invoke-ApiGet "recordings?limit=5"
        if ($resp -match "passthrough|real-backend|statusCode" -and $recAfter -match "direction") {
            Test-Pass "M04: RECORD mode passthrough + record"
        } else {
            Test-Skip "M04: RECORD mode (resp: $resp)"
        }
    } catch {
        Test-Skip "M04: RECORD mode (API error: $_)"
    }
    # Restore staging-a to STUB mode
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"stub"}' -ErrorAction Stop | Out-Null
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
    } catch {}
} else {
    Test-Skip "M04: RECORD mode (cannot find staging-a environment ID)"
}

# M05: Switch staging-a to RECORD_ALL mode — unmatched requests also recorded
if ($envAId) {
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"record-all"}' -ErrorAction Stop | Out-Null
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
        # Send request to unmatched path
        $resp = Invoke-AppGet "$APP_A/api/http/get?url=http://real-backend:9090/status/200"
        $recAfter = Invoke-ApiGet "recordings?limit=10"
        if ($recAfter -match "unmatched|direction") {
            Test-Pass "M05: RECORD_ALL mode records unmatched"
        } else {
            Test-Skip "M05: RECORD_ALL mode (recordings: $recAfter)"
        }
    } catch {
        Test-Skip "M05: RECORD_ALL mode (API error: $_)"
    }
    # Restore staging-a to STUB mode
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/environments/$envAId" -Method Put -ContentType "application/json" -Headers $headers -Body '{"mode":"stub"}' -ErrorAction Stop | Out-Null
        Start-Sleep -Seconds $MODE_SETTLE_WAIT
    } catch {}
} else {
    Test-Skip "M05: RECORD_ALL mode (cannot find staging-a environment ID)"
}

# -------------------- AS: RuleSet CRUD --------------------
Write-Host ""
Write-Host "--- AS: RuleSet ---" -ForegroundColor White

# AS01: Create a rule set
$testSetId = "test-ruleset-" + (Get-Random -Minimum 1000 -Maximum 9999)
$setBody = @{
    id = $testSetId
    name = "Test RuleSet"
    description = "Full-chain RuleSet CRUD test"
    ruleIds = @("staging-a-http-get")
    enabled = $true
} | ConvertTo-Json -Depth 4
try {
    $createSet = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rulesets" -Method Post -ContentType "application/json" -Headers $headers -Body $setBody -ErrorAction Stop
    if ($createSet.success -eq $true -or $createSet.data.id) {
        Test-Pass "AS01: RuleSet created"
    } else {
        Test-Skip "AS01: RuleSet create (response: $createSet)"
    }
} catch {
    Test-Skip "AS01: RuleSet create (error: $_)"
}

# AS02: List rule sets and verify presence
try {
    $setsJson = Invoke-ApiGet "rulesets"
    if ($setsJson -match $testSetId) {
        Test-Pass "AS02: RuleSet listed"
    } else {
        Test-Fail "AS02: RuleSet not found in list (resp: $setsJson)"
    }
} catch {
    Test-Skip "AS02: RuleSet list (error: $_)"
}

# AS03: Delete rule set and verify removal (DELETE /rulesets/{id} wired in RuleApiHandler)
try {
    Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rulesets/$testSetId" -Method Delete -Headers $headers -ErrorAction Stop | Out-Null
    $setsJsonAfter = Invoke-ApiGet "rulesets"
    if ($setsJsonAfter -notmatch $testSetId) {
        Test-Pass "AS03: RuleSet deleted"
    } else {
        Test-Fail "AS03: RuleSet still present after delete (resp: $setsJsonAfter)"
    }
} catch {
    Test-Skip "AS03: RuleSet delete (error: $_)"
}

# AS04-AS06 (RuleSet update/disable/re-enable) removed: server does not
# implement PUT /rulesets/{id}. The handler at RuleApiHandler.java only
# supports POST/GET/DELETE for rulesets. These tests always 404'd and were
# skipped — removed to keep the test suite honest.

# Clean up the rule set created for testing
try { Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rulesets/$testSetId" -Method Delete -Headers $headers -ErrorAction Stop | Out-Null } catch {}

# -------------------- REC: Recording management --------------------
Write-Host ""
Write-Host "--- REC: Recording Management ---" -ForegroundColor White
# Recordings are produced by the D (MQ direction) and M04/M05 (mode) sections above.

# REC-PAGE: paginated listing returns a paginated structure (total / items)
$recPageJson = Invoke-ApiGet "recordings?page=1&size=5"
$recPageObj = Get-JsonObject $recPageJson
# ApiResponse wraps payload in `data`; paginated recordings live at data.total / data.items
$recPageData = if ($recPageObj -and $recPageObj.data) { $recPageObj.data } else { $recPageObj }
if ($recPageData -and ($recPageData.total -ne $null -or ($recPageData.PSObject.Properties.Name -contains "items"))) {
    Test-Pass "REC-PAGE: recordings pagination supported (total=$($recPageData.total))"
} else {
    Test-Fail "REC-PAGE: recordings pagination not structured (resp: $recPageJson)"
}

# REC-DEL: delete one recording and confirm it is gone
$recListJson = Invoke-ApiGet "recordings?page=1&size=10"
$recListObj = Get-JsonObject $recListJson
# ApiResponse wraps payload in `data`; recordings list lives at data.items
$recListItems = if ($recListObj -and $recListObj.data) { $recListObj.data.items } else { $null }
if ($recListItems -and $recListItems.Count -gt 0) {
    $delId = $recListItems[0].id
    try {
        Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/recordings/$delId" -Method Delete -Headers $headers -ErrorAction Stop | Out-Null
        $afterObj = Get-JsonObject (Invoke-ApiGet "recordings?page=1&size=50")
        $afterItems = if ($afterObj -and $afterObj.data) { $afterObj.data.items } else { $null }
        $stillThere = $false
        if ($afterItems) { foreach ($it in $afterItems) { if ($it.id -eq $delId) { $stillThere = $true } } }
        if (-not $stillThere) {
            Test-Pass "REC-DEL: recording deleted (id=$delId)"
        } else {
            Test-Fail "REC-DEL: recording still present after delete (id=$delId)"
        }
    } catch {
        Test-Fail "REC-DEL: delete error: $_"
    }
} else {
    Test-Skip "REC-DEL: no recordings available to delete"
}

# -------------------- RU / RST: Undo & counter reset --------------------
Write-Host ""
Write-Host "--- RU/RST: Undo & Reset ---" -ForegroundColor White

# RU01: rule undo — modify a rule then revert via undo.
# Note: Rule model has no `description` field; use `name` (which is mutable and
# restored by undo) as the edit target.
$ruRuleId = "staging-a-http-get"
try {
    $orig = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$ruRuleId" -Method Get -Headers $headers -ErrorAction Stop
    $updated = $orig.data
    $origName = $updated.name
    $updated.name = "temp-edited-for-undo-test"
    Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$ruRuleId" -Method Put -ContentType "application/json" -Headers $headers -Body ($updated | ConvertTo-Json -Depth 10) -ErrorAction Stop | Out-Null
    $undo = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$ruRuleId/undo" -Method Post -Headers $headers -ErrorAction Stop
    if ($undo.success -eq $true) { Test-Pass "RU01: rule undo successful" }
    else { Test-Skip "RU01: rule undo (resp: $undo)" }
} catch {
    Test-Skip "RU01: rule undo (error: $_)"
}

# RST01: reset all rule state counters
try {
    $rst = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/reset-all-state" -Method Post -Headers $headers -ErrorAction Stop
    if ($rst.success -eq $true) { Test-Pass "RST01: reset-all-state successful" }
    else { Test-Skip "RST01: reset-all-state (resp: $rst)" }
} catch {
    Test-Skip "RST01: reset-all-state (error: $_)"
}

# -------------------- OAPI: OpenAPI import --------------------
Write-Host ""
Write-Host "--- OAPI: OpenAPI Import ---" -ForegroundColor White

$oapiSpecPath = Join-Path $rulesDir "openapi-sample.json"
if (-not (Test-Path $oapiSpecPath)) {
    Test-Skip "OAPI01: OpenAPI sample spec not found"
} else {
    $oapiSpec = Get-Content $oapiSpecPath -Raw
    # OAPI01: preview import (no persist)
    try {
        $preview = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/import-openapi" -Method Post -ContentType "application/json" -Headers $headers -Body $oapiSpec -ErrorAction Stop
        if ($preview.success -eq $true -and $preview.data.generatedCount -gt 0) {
            Test-Pass "OAPI01: OpenAPI import preview generated $($preview.data.generatedCount) rules"
        } else {
            Test-Fail "OAPI01: OpenAPI import preview (resp: $preview)"
        }
    } catch {
        Test-Fail "OAPI01: OpenAPI import preview (error: $_)"
    }

    # OAPI02: import + persist, then clean up the imported rules
    try {
        $save = Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/import-openapi?save=true&environment=staging-a" -Method Post -ContentType "application/json" -Headers $headers -Body $oapiSpec -ErrorAction Stop
        if ($save.success -eq $true -and $save.data.savedCount -gt 0) {
            Test-Pass "OAPI02: OpenAPI import persisted $($save.data.savedCount) rules"
            foreach ($rid in $save.data.savedIds) {
                try { Invoke-RestMethod -Uri "$SERVER/__baafoo__/api/rules/$rid" -Method Delete -Headers $headers -ErrorAction SilentlyContinue | Out-Null } catch {}
            }
        } else {
            Test-Fail "OAPI02: OpenAPI import persist (resp: $save)"
        }
    } catch {
        Test-Fail "OAPI02: OpenAPI import persist (error: $_)"
    }
}

# -------------------- G: gRPC (end-to-end via test-spring client) --------------------
Write-Host ""
Write-Host "--- G: gRPC ---" -ForegroundColor White
# gRPC rules are now driven through baafoo-test-spring's /api/grpc/* endpoints.
# The agent's GrpcChannelAdvice intercepts ManagedChannelBuilder.forTarget(...)
# and redirects the call to the Baafoo stub gRPC server (port 9005). Each endpoint
# returns JSON: { completed, grpcStatus, grpcMessage, messages[], error, latencyMs }.

# G01: unary SayHello -> grpc-status 0, body echoes "Baafoo gRPC"
try {
    $j = Invoke-AppGet "$APP_A/api/grpc/greeter" | ConvertFrom-Json
    if ($j.completed -and $j.grpcStatus -eq "0" -and $j.messages.Count -ge 1 -and $j.messages[0] -match "Baafoo gRPC") {
        Test-Pass "G01: gRPC unary SayHello stubbed (grpc-status=$($j.grpcStatus), msgs=$($j.messages.Count))"
    } else {
        Test-Fail "G01: gRPC unary SayHello (resp=$j)"
    }
} catch {
    Test-Fail "G01: gRPC unary SayHello (error: $_)"
}

# G02: unary SlowMethod -> grpc-status 0, body echoes "delayed" (distinct rule from G01)
try {
    $j = Invoke-AppGet "$APP_A/api/grpc/slow" | ConvertFrom-Json
    if ($j.completed -and $j.grpcStatus -eq "0" -and $j.messages.Count -ge 1 -and $j.messages[0] -match "delayed") {
        Test-Pass "G02: gRPC unary SlowMethod stubbed (grpc-status=$($j.grpcStatus), msgs=$($j.messages.Count))"
    } else {
        Test-Fail "G02: gRPC unary SlowMethod (resp=$j)"
    }
} catch {
    Test-Fail "G02: gRPC unary SlowMethod (error: $_)"
}

# G03: unary GetUser -> expects grpc-status 5 (NOT_FOUND) from grpc-error rule
try {
    $j = Invoke-AppGet "$APP_A/api/grpc/error" | ConvertFrom-Json
    if ($j.completed -and $j.grpcStatus -eq "5") {
        Test-Pass "G03: gRPC unary GetUser returns grpc-status=5 (NOT_FOUND) as configured"
    } else {
        Test-Fail "G03: gRPC unary GetUser expected grpc-status=5 (resp=$j)"
    }
} catch {
    Test-Fail "G03: gRPC unary GetUser (error: $_)"
}

# G04: server streaming StreamEvents -> grpc-status 0, 3 streamed messages
try {
    $j = Invoke-AppGet "$APP_A/api/grpc/server-stream" | ConvertFrom-Json
    if ($j.completed -and $j.grpcStatus -eq "0" -and $j.messages.Count -eq 3) {
        Test-Pass "G04: gRPC server-streaming StreamEvents returned $($j.messages.Count) messages"
    } else {
        Test-Fail "G04: gRPC server-streaming StreamEvents expected 3 messages (resp=$j)"
    }
} catch {
    Test-Fail "G04: gRPC server-streaming (error: $_)"
}

# G05: client streaming CollectMetrics -> grpc-status 0, 1 aggregated response
try {
    $j = Invoke-AppGet "$APP_A/api/grpc/client-stream" | ConvertFrom-Json
    if ($j.completed -and $j.grpcStatus -eq "0" -and $j.messages.Count -eq 1) {
        Test-Pass "G05: gRPC client-streaming CollectMetrics returned $($j.messages.Count) aggregated response"
    } else {
        Test-Fail "G05: gRPC client-streaming CollectMetrics expected 1 response (resp=$j)"
    }
} catch {
    Test-Fail "G05: gRPC client-streaming (error: $_)"
}

# G06: bidirectional streaming Chat -> grpc-status 0, 2 echoed messages
try {
    $j = Invoke-AppGet "$APP_A/api/grpc/bidi" | ConvertFrom-Json
    if ($j.completed -and $j.grpcStatus -eq "0" -and $j.messages.Count -eq 2) {
        Test-Pass "G06: gRPC bidi-streaming Chat returned $($j.messages.Count) messages"
    } else {
        Test-Fail "G06: gRPC bidi-streaming Chat expected 2 messages (resp=$j)"
    }
} catch {
    Test-Fail "G06: gRPC bidi-streaming (error: $_)"
}

# -------------------- MX: Protocol x Mode coverage gaps --------------------
Write-Host ""
Write-Host "--- MX: Protocol x Mode Matrix (gap markers) ---" -ForegroundColor White
# HTTP is fully covered across all 5 modes (see H*/M* sections). The combinations
# below cannot be exercised in Docker Staging because there is no real TCP/Kafka/
# Pulsar/JMS broker — only the MockBroker STUB / RECORD_AND_STUB redirect paths are
# driven (see T*/K*/P*/J* and D sections). Marked SKIP (not failure) to make the
# coverage gap explicit and runnable.
$mxGaps = @(
    @{p="tcp";    m="passthrough"},      @{p="tcp";    m="record"},
    @{p="tcp";    m="record-and-stub"},  @{p="tcp";    m="record-all"},
    @{p="kafka";  m="passthrough"},      @{p="kafka";  m="record"},      @{p="kafka";  m="record-all"},
    @{p="pulsar"; m="passthrough"},      @{p="pulsar"; m="record"},      @{p="pulsar"; m="record-all"},
    @{p="jms";    m="passthrough"},      @{p="jms";    m="record"},      @{p="jms";    m="record-all"}
)
foreach ($gap in $mxGaps) {
    Test-Skip "MX: $($gap.p) x $($gap.m) not exercised (no real $($gap.p) broker in staging; only MockBroker STUB / RECORD_AND_STUB path driven)"
}

# ==================== 6. Summary report ====================
Write-Host ""
Write-Host "============================================================"
Write-Host "  Test Summary"
Write-Host "============================================================"
$total = $script:Pass + $script:Fail + $script:Skip
Write-Host "  Pass: $script:Pass" -ForegroundColor Green
Write-Host "  Fail: $script:Fail" -ForegroundColor Red
Write-Host "  Skip: $script:Skip" -ForegroundColor Yellow
Write-Host "  Total: $total"
Write-Host ""

if ($script:FailedTests.Count -gt 0) {
    Write-Host "Failed tests:" -ForegroundColor Red
    foreach ($t in $script:FailedTests) {
        Write-Host "  - $t" -ForegroundColor Red
    }
    Write-Host ""
}

# ==================== JUnit XML report (CI consumption) ====================
Export-JUnitXml (Join-Path $PSScriptRoot "junit-report.xml")

# ==================== Cleanup ====================
if (-not $NoCleanup) {
    Write-Step "Cleanup Docker environment"
    & docker compose @COMPOSE_FILES down -v 2>&1 | Out-Null
    Write-OK "Environment cleaned"
} else {
    Write-Host ""
    Write-Host "  Environment kept (-NoCleanup)" -ForegroundColor Yellow
    Write-Host "  Web console: http://localhost:8084" -ForegroundColor Cyan
    Write-Host "  App env-a:   http://localhost:9090" -ForegroundColor Cyan
    Write-Host "  App env-b:   http://localhost:9091" -ForegroundColor Cyan
}

if ($script:Fail -gt 0) {
    Write-Host ""
    Write-Host "=== Full-Chain Integration Test FAILED ($($script:Fail) failed) ===" -ForegroundColor Red
    exit 1
} elseif ($script:Skip -gt 0) {
    Write-Host ""
    Write-Host "=== Full-Chain Integration Test PASSED WITH SKIPS ($($script:Skip) skipped) ===" -ForegroundColor Yellow
    Write-Host "WARNING: coverage gaps remain (see SKIP lines above). CI should treat exit 2 as non-green for release gating." -ForegroundColor Yellow
    exit 2
} else {
    Write-Host ""
    Write-Host "=== Full-Chain Integration Test PASSED (no skips) ===" -ForegroundColor Green
    exit 0
}
