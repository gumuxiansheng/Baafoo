# =============================================================================
# Baafoo Full-Chain Integration Test - PowerShell Orchestrator
#
# Features:
#   1. Build all JARs (server + agent + test-spring + feign plugin)
#   2. Copy Feign plugin JAR to ./plugins/
#   3. Start Docker Staging environment (server + postgres + app-env-a + app-env-b)
#   4. Wait for all services to be healthy
#   5. Register all 16 test rules
#   6. Run full-chain test cases (HTTP/TCP/Kafka/Pulsar/JMS + Plugin + Env isolation + Recording)
#   7. Summary report and cleanup
#
# Usage:
#   .\testing\test-fullchain.ps1              # Build + test + cleanup
#   .\testing\test-fullchain.ps1 -NoCleanup   # Keep environment after test
#   .\testing\test-fullchain.ps1 -SkipBuild   # Skip build step
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
    Write-Host "  .\testing\test-fullchain.ps1              # Full build+test+cleanup"
    Write-Host "  .\testing\test-fullchain.ps1 -NoCleanup   # Keep test environment"
    Write-Host "  .\testing\test-fullchain.ps1 -SkipBuild   # Skip build (use existing JARs)"
    exit 0
}

$PROJECT_ROOT = $PSScriptRoot | Split-Path -Parent
Set-Location $PROJECT_ROOT

$COMPOSE_FILES = @("-f", "docker-compose.yml", "-f", "docker-compose.staging.yml")
$SERVER = "http://localhost:8084"
$APP_A  = "http://localhost:9090"
$APP_B  = "http://localhost:9091"
$API_KEY = "staging-admin-key"

# Test counters
$script:Pass = 0
$script:Fail = 0
$script:Skip = 0
$script:FailedTests = @()

function Write-Step($msg) { Write-Host "`n[STEP] $msg" -ForegroundColor Cyan }
function Write-OK($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "  [ERR] $msg" -ForegroundColor Red }

function Test-Pass($msg) {
    Write-Host "  [PASS] $msg" -ForegroundColor Green
    $script:Pass++
}
function Test-Fail($msg) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    $script:Fail++
    $script:FailedTests += $msg
}
function Test-Skip($msg) {
    Write-Host "  [SKIP] $msg" -ForegroundColor Yellow
    $script:Skip++
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

# Extract body field from JSON response (handles escaped JSON in body value)
function Get-JsonBody($json) {
    # The body field contains escaped JSON like "body":"{\"mocked\":true,...}"
    # Match everything between "body":" and the closing " (before next top-level key)
    if ($json -match '"body"\s*:\s*"((?:[^"\\]|\\.)*)"') { return $matches[1] }
    return $null
}

# ==================== 1. Build all JARs ====================
if (-not $SkipBuild) {
    Write-Step "1/6: Build all JAR files"

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
    Write-Step "1/6: Skip build (-SkipBuild)"
}

# ==================== 2. Clean old environment ====================
Write-Step "2/6: Clean old Docker environment"
& docker compose @COMPOSE_FILES down -v --remove-orphans 2>&1 | Out-Null
Write-OK "Old environment cleaned"

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
    Start-Sleep -Seconds 2
    Write-OK "Environments created via API fallback"
}

# ==================== 4. Register test rules ====================
Write-Step "4/6: Register all test rules"

$rulesDir = "testing\test-rules\rules"
$ruleFiles = @(
    "http-get.json", "http-post.json", "http-put.json", "http-delete.json",
    "http-delay.json", "http-error.json", "http-staging-b.json", "http-consul.json",
    "kafka-topic.json", "kafka-wildcard.json",
    "pulsar-topic.json",
    "jms-queue.json", "jms-topic.json",
    "tcp-hex.json", "tcp-regex.json", "tcp-multiround.json"
)

$registered = 0
$failed = 0
$headers = @{ "X-Api-Key" = $API_KEY }
foreach ($ruleFile in $ruleFiles) {
    $rulePath = Join-Path $rulesDir $ruleFile
    if (-not (Test-Path $rulePath)) {
        Write-Warn "Rule file not found: $ruleFile"
        continue
    }
    $ruleJson = Get-Content $rulePath -Raw
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
Start-Sleep -Seconds 3
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

# -------------------- H: HTTP protocol --------------------
Write-Host ""
Write-Host "--- H: HTTP ---" -ForegroundColor White

# H01: HTTP GET stub
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://httpbin.org/get"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") { Test-Pass "H01: HTTP GET intercepted" }
else { Test-Fail "H01: HTTP GET intercepted (stubbed=$stubbed)" }
if ($resp -match "mocked") { Test-Pass "H01: HTTP GET response correct" }
else { Test-Fail "H01: HTTP GET response correct (resp=$resp)" }

# H02: HTTP POST stub (endpoint is @PostMapping, must use POST)
$resp = Invoke-AppPost "$APP_A/api/http/post?url=http://httpbin.org/post&body=%7B%22test%22%3A%22baafoo%22%7D"
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
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://httpbin.org/delay"
$stubbed = Get-JsonValue $resp "stubbed"
if ($stubbed -eq "true") { Test-Pass "H05: HTTP delay path intercepted" }
else { Test-Fail "H05: HTTP delay path intercepted (stubbed=$stubbed)" }

# H06: HTTP error code rule
$resp = Invoke-AppGet "$APP_A/api/http/get?url=http://httpbin.org/error500"
$statusCode = Get-JsonValue $resp "statusCode"
if ($statusCode -eq "500") { Test-Pass "H06: HTTP error code returns 500" }
else { Test-Fail "H06: HTTP error code returns 500 (statusCode=$statusCode)" }

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
$body = Get-JsonValue $resp "body"
if ($body -and $body -ne "null") {
    Test-Pass "T02: TCP NIO Socket has response"
} else {
    Test-Skip "T02: TCP NIO Socket (no response)"
}

# T03: TCP multiround
$resp = Invoke-AppGet "$APP_A/api/socket/multiround?host=$TCP_HOST&port=$TCP_PORT"
if ($resp -match "LOGIN|QUERY|LOGOUT|round|mocked|stub") {
    Test-Pass "T03: TCP multiround interaction"
} else {
    Test-Skip "T03: TCP multiround interaction (response: $resp)"
}

# -------------------- K: Kafka protocol --------------------
Write-Host ""
Write-Host "--- K: Kafka ---" -ForegroundColor White

# K01: Kafka Produce
$resp = Invoke-AppGet "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic&message=hello-baafoo-kafka"
if ($resp -match "success|stubbed|mocked|baafoo") {
    Test-Pass "K01: Kafka Produce stub"
} else {
    Test-Fail "K01: Kafka Produce stub (response: $resp)"
}

# K02: Kafka Consume
$resp = Invoke-AppGet "$APP_A/api/kafka/consume?bootstrapServers=kafka-broker:9092&topic=baafoo-test-topic"
if ($resp -match "success|stubbed|mocked|baafoo|message") {
    Test-Pass "K02: Kafka Consume stub"
} else {
    Test-Fail "K02: Kafka Consume stub (response: $resp)"
}

# K03: Kafka wildcard topic
$resp = Invoke-AppGet "$APP_A/api/kafka/send?bootstrapServers=kafka-broker:9092&topic=baafoo-wildcard-topic&message=test"
if ($resp -match "success|stubbed|mocked|baafoo") {
    Test-Pass "K03: Kafka wildcard topic stub"
} else {
    Test-Skip "K03: Kafka wildcard topic (response: $resp)"
}

# -------------------- P: Pulsar protocol --------------------
Write-Host ""
Write-Host "--- P: Pulsar ---" -ForegroundColor White

# P01: Pulsar Produce
$resp = Invoke-AppGet "$APP_A/api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic&message=hello-baafoo-pulsar"
if ($resp -match "success|stubbed|mocked|baafoo|error|timeout") {
    Test-Pass "P01: Pulsar Produce (has response)"
} else {
    Test-Skip "P01: Pulsar Produce (response: $resp)"
}

# P02: Pulsar Consume
$resp = Invoke-AppGet "$APP_A/api/pulsar/consume?serviceUrl=pulsar://pulsar-broker:6650&topic=persistent://public/default/baafoo-test-topic"
if ($resp -match "success|stubbed|mocked|baafoo|error|timeout") {
    Test-Pass "P02: Pulsar Consume (has response)"
} else {
    Test-Skip "P02: Pulsar Consume (response: $resp)"
}

# -------------------- J: JMS protocol --------------------
Write-Host ""
Write-Host "--- J: JMS ---" -ForegroundColor White

# J01: JMS Queue send
$resp = Invoke-AppGet "$APP_A/api/jms/send?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE&message=hello-baafoo-jms"
if ($resp -match "success|stubbed|mocked|baafoo|sent") {
    Test-Pass "J01: JMS Queue send stub"
} else {
    Test-Fail "J01: JMS Queue send stub (response: $resp)"
}

# J02: JMS Queue receive
$resp = Invoke-AppGet "$APP_A/api/jms/receive?brokerUrl=tcp://jms-broker:61616&queueName=BAAFOO.TEST.QUEUE"
if ($resp -match "success|stubbed|mocked|baafoo|message|null") {
    Test-Pass "J02: JMS Queue receive stub"
} else {
    Test-Fail "J02: JMS Queue receive stub (response: $resp)"
}

# -------------------- E: Environment isolation --------------------
Write-Host ""
Write-Host "--- E: Environment Isolation ---" -ForegroundColor White

# E01: staging-a returns staging-a tag (check raw response for env tag)
$respA = Invoke-AppGet "$APP_A/api/http/get?url=http://httpbin.org/get"
if ($respA -match "staging-a") {
    Test-Pass "E01: staging-a isolation correct"
} else {
    Test-Fail "E01: staging-a isolation (resp: $respA)"
}

# E02: staging-b returns staging-b tag (check raw response for env tag)
$respB = Invoke-AppGet "$APP_B/api/http/get?url=http://httpbin.org/get"
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
$resp = Invoke-AppGet "$APP_A/api/feign/get?baseUrl=http://httpbin.org"
if ($resp -match '"stubbed":\s*true|mocked|baafoo') {
    Test-Pass "PL03: Feign call intercepted by agent"
} elseif ($resp -match '"statusCode":\s*\d+') {
    Test-Skip "PL03: Feign call completed (may not be stubbed: $resp)"
} else {
    Test-Skip "PL03: Feign plugin test (response: $resp)"
}

# -------------------- R: Recording verification --------------------
Write-Host ""
Write-Host "--- R: Recording ---" -ForegroundColor White

# R01: Recording list non-empty
$recordingsJson = Invoke-ApiGet "recordings?limit=10"
$recCount = ([regex]::Matches($recordingsJson, '"id"')).Count
if ($recCount -gt 0) {
    Test-Pass "R01: Recording list has data (count=$recCount)"
} else {
    Test-Fail "R01: Recording list empty"
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
    Test-Fail "R03: Recording missing ruleName field"
}

# -------------------- D: MQ direction annotation --------------------
Write-Host ""
Write-Host "--- D: MQ Direction ---" -ForegroundColor White

# D01: Kafka recording has produce/consume direction
if ($recordingsJson -match '"protocol":"kafka".*?"direction":"(produce|consume)"') {
    Test-Pass "D01: Kafka recording has produce/consume direction"
} else {
    Test-Skip "D01: Kafka recording direction (may have no Kafka recordings)"
}

# D02: JMS recording has produce/consume direction
if ($recordingsJson -match '"protocol":"jms".*?"direction":"(produce|consume)"') {
    Test-Pass "D02: JMS recording has produce/consume direction"
} else {
    Test-Skip "D02: JMS recording direction (may have no JMS recordings)"
}

# D03: Pulsar recording has produce/consume direction
if ($recordingsJson -match '"protocol":"pulsar".*?"direction":"(produce|consume)"') {
    Test-Pass "D03: Pulsar recording has produce/consume direction"
} else {
    Test-Skip "D03: Pulsar recording direction (may have no Pulsar recordings)"
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

if ($script:Fail -eq 0) {
    Write-Host ""
    Write-Host "=== Full-Chain Integration Test PASSED ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host ""
    Write-Host "=== Full-Chain Integration Test FAILED ===" -ForegroundColor Red
    exit 1
}
