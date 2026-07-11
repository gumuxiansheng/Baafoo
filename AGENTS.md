# Baafoo — Agent Guide

## Build

```bash
mvnw clean package -DskipTests              # full build
mvnw clean package -pl baafoo-agent -am -DskipTests   # agent only
mvnw clean package -pl baafoo-server -am -DskipTests  # server (also builds web/ frontend)
```

Server build auto-runs `npm install && npm run build` in `web/`, then copies `web/dist/` into the shade JAR. Node.js 16+ is required.

## Test

```bash
mvnw test                                      # all modules
mvnw test -pl baafoo-core                      # single module
mvnw test -pl baafoo-test-spring               # Spring Boot IT (random port, no setup)
```

No lint/typecheck commands — just the Maven lifecycle. Jacoco coverage runs at `prepare-package` phase; excludes `**/entity/*Entity.class` and `**/mapper/*Mapper.class`.

## Modules & Boundaries

| Module | Entrypoint | Notes |
|--------|-----------|-------|
| `baafoo-core` | — | shared models, config, `MatchEngine` |
| `baafoo-plugin-api` | `AgentPlugin` SPI | **zero external deps** (loaded by App CL / `PluginClassLoader.spiLoader`, NOT Bootstrap CL) |
| `baafoo-agent` | `BaafooAgent#premain` | shade + **relocate** Jackson/SLF4J/SnakeYAML/baafoo-core; **ByteBuddy NOT relocated** |
| `baafoo-server` | `BaafooServer#main` | Netty, H2/PostgreSQL, MyBatis, HikariCP, JWT |
| `baafoo-cli` | `BaafooCli#main` | `baafoo init` scaffolding tool |
| `baafoo-test-app` | `BaafooTestApp` / `QuickTest` | interactive multi-protocol tester (no Spring) |
| `baafoo-test-spring` | Spring Boot on port 9090 | Spring Boot multi-protocol tester |
| `baafoo-example-plugins/feign` | `FeignPlugin` | demo plugin via SPI |
| `web/` | Vue 3 + Vite | `npm run dev` on port 3000 |

## Agent Shade Rules (Critical)

- `baafoo-agent/pom.xml` relocates `com.fasterxml.jackson`, `org.slf4j`, `com.baafoo.core`, `org.yaml.snakeyaml` into `com.baafoo.agent.shaded.*`
- **`com.baafoo.plugin` is NOT relocated** — ServiceLoader SPI discovery requires the original package name to match `META-INF/services/com.baafoo.plugin.AgentPlugin` in plugin JARs. Relocating it breaks plugin loading.
- **ByteBuddy is NOT relocated** (breaks internal package-private access at runtime)
- The manifest sets `Premain-Class: com.baafoo.agent.BaafooAgent` with `Can-Redefine-Classes/Can-Retransform-Classes/Can-Set-Native-Method-Prefix: true`

## Plugin SPI

- Implement `com.baafoo.plugin.AgentPlugin` in a standalone JAR
- Register in `META-INF/services/com.baafoo.plugin.AgentPlugin`
- Place JAR in `./plugins/` at runtime
- Plugin uses a separate `PluginClassLoader` (parent=null) — full isolation from host app
- For the Feign demo, see `baafoo-example-plugins/feign/`

## Runtime

```bash
# Server
java -jar baafoo-server/target/baafoo-server-1.1.0-SNAPSHOT.jar baafoo-server.yml

# App with Agent
java -javaagent:baafoo-agent/target/baafoo-agent-1.1.0-SNAPSHOT.jar=config=baafoo-agent.yml -jar your-app.jar
```

- Java 9+ requires `--add-opens java.base/java.net=ALL-UNNAMED`
- Server ports: 8084 (API+Web console), 9000–9005 (HTTP/TCP/Kafka/Pulsar/JMS/gRPC stub), 9005 (gRPC unified, handles Unary + Streaming over HTTP/2)
- All API paths: `/__baafoo__/api/*`
- Server defaults: H2 embedded DB, `unmatchedDefault: passthrough`

### Agent Authentication

When server has `auth.enabled: true`, the agent must send `X-Api-Key` header on all ControlChannel requests (heartbeat, registration, rule polling). Configure in agent YAML:

```yaml
server:
  host: server
  port: 8084
  apiKey: "your-api-key"
```

`ControlChannel.applyApiKey()` adds the header automatically when `apiKey` is non-empty.

### Environment Modes

Each environment has a `mode` field (`EnvironmentMode` enum) controlling agent behavior:

| Mode | Behavior |
|------|----------|
| `STUB` | Returns stubbed response, no forwarding |
| `PASSTHROUGH` | Forwards request to real backend, no stubbing |
| `RECORD` | Forwards request and records response |
| `RECORD_AND_STUB` | Returns stubbed response AND records real response |
| `RECORD_ALL` | Returns stubbed response and records all traffic (including unmatched) |

### Rule Priority

**Lower number = higher priority.** Default priority is 100. Rules are sorted ascending by priority; `MatchEngine` uses first-match-wins semantics.

## Testing (integration)

Integration test assets live in `testing/`:

```
testing/
  1_UnitTest/                  # Mutation testing tooling
  2_IntegrationTest/           # Integration test scripts + rule JSON files
    rules/                     # 37 JSON rule files (all protocols, condition types, modes)
    register-rules.ps1         # PowerShell script to register all rules via API
    register-all.sh            # Bash equivalent
    test-integration.ps1/.sh   # End-to-end integration test script
  3_SystemTest/                # Full-chain system test (Docker staging)
    test-fullchain.ps1         # Full-chain integration test (88 test cases, Docker-based)
    test-fullchain.sh          # Bash equivalent
    TEST-MANUAL.md             # Full test manual (architecture, matrix, steps, acceptance criteria)
    TEST-REPORT.md             # Latest test report
    junit-report.xml           # JUnit XML output for CI
  4_E2ETest/                   # Enterprise end-to-end tests (Kafka, PetClinic, SCA)
  6_UITest/                    # Playwright UI tests
  7_Others/                    # Test plan, coverage review, other docs
    tmp/                       # Scratch dir for test runs (gitignored, never commit)
    PROJECT-TEST-PLAN.md       # Master test plan
```

### Test Categories (88 cases in test-fullchain.ps1)

| Category | IDs | Description |
|----------|-----|-------------|
| F: Core | F01–F05 | Health check, DB, rule registration, env-a/b health |
| A: API Security & CRUD | A01–A07 | API key auth, rule/environment CRUD |
| H: HTTP | H01–H09 | GET/POST/PUT/DELETE stub, delay, error, GraphQL, request-count, Consul |
| T: TCP | T01–T03 | TCP BIO stub, NIO socket, multi-round |
| K: Kafka | K01–K03 | Kafka produce/consume, wildcard topic |
| P: Pulsar | P01–P03 | Pulsar produce/consume, wildcard topic |
| J: JMS | J01–J02 | JMS queue produce/consume |
| E: Environment | E01–E02 | Environment isolation (staging-a vs staging-b) |
| PL: Plugin | PL01–PL03 | Plugin SPI load, agent heartbeat, Feign functional test |
| R: Recording | R01–R03 | Recording list has data, direction/ruleName fields |
| D: MQ Direction | D01–D03 | Kafka/JMS/Pulsar recording has produce/consume direction |
| C: Condition Types | C01–C10 | header/query/body/bodyJsonPath/contains/endsWith/regex/exists/caseInsensitive/disabled |
| M: Environment Modes | M01–M05 | STUB/PASSTHROUGH/RECORD/RECORD_AND_STUB/RECORD_ALL |
| AS: RuleSet | AS01–AS03 | RuleSet create/list/delete (PUT update/disable not implemented server-side) |
| REC: Recording Mgmt | REC-PAGE, REC-DEL | Recording pagination, deletion |
| RU/RST: Undo & Reset | RU01, RST01 | Rule undo, reset-all-state counters |
| OAPI: OpenAPI Import | OAPI01–OAPI02 | OpenAPI import preview + persist |
| G: gRPC | G01–G06 | Unary (SayHello, SlowMethod, GetUser-error), server-streaming, client-streaming, bidi-streaming |
| MX: Protocol×Mode Matrix | (12 skips) | Gap markers — no real MQ broker in staging, only STUB/RECORD_AND_STUB exercised |

### Rule File Coverage (37 files)

**Protocol rules (22):** http-get, http-post, http-put, http-delete, http-delay, http-error, http-staging-b, http-consul, http-graphql, http-request-count, http-caseinsensitive, grpc-greeter, grpc-error, grpc-delay, grpc-server-streaming, grpc-client-streaming, grpc-bidirectional-streaming, tcp-hex, tcp-regex, tcp-multiround, kafka-topic, kafka-wildcard, kafka-header, pulsar-topic, pulsar-wildcard, jms-queue, jms-topic

**Condition type rules (9):** http-header, http-query, http-body, http-jsonpath, http-contains, http-endswith, http-path-regex, http-header-exists, http-caseinsensitive

**Special rules (4):** http-disabled (enabled=false), http-no-env (global rule), openapi-sample (OpenAPI import test)

**Condition types covered:** method, path, topic, header, query, body, bodyJsonPath, graphqlOperationName, graphqlOperationType, requestCount, grpcService, grpcMethod

**Operators covered:** equals, contains, startsWith, endsWith, regex, exists

**Rule**: All intermediate/temp files generated during test execution (logs, dumps, temp configs, etc.) must be written to `testing/7_Others/tmp/`. This directory is gitignored. Never write test artifacts to the project root or module directories.

Docker staging environment:

```bash
# Start staging cluster (all services including staging-init)
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build

# Rebuild specific services after code changes
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build server app-env-a
```

Staging environments: `staging-a` (STUB mode), `staging-b` (STUB mode), `staging-c` (fallback). Agent configs include `apiKey: "staging-admin-key"` for server authentication.

## Documentation

Documentation lives in `.workmemo/`. Follow these rules when writing or updating docs:

- **Pre-commit check**: Before each commit, update any `.workmemo/` documents affected by the change. If a feature, design decision, or architectural change is being committed, the corresponding "why" doc must reflect it.
- **"Why" not "what"**: Only write docs that explain *why* a decision was made or *why* an approach was chosen. If the doc describes *what* the code does, put that in code comments instead.
- **Auto-generatable → automate**: If content can be derived from git log, code types, or test output, set up a script rather than writing manually. See `gen-changelog.ps1` for reference.
- **Stale docs**: If a doc would become seriously misleading after 6 months without updates, either make it auto-validatable or don't write it. Archive superseded docs to `archive/` under the relevant directory.

## Known Issues

See `.review/deep-code-review-report.md` for 20+ verified findings. The following P0 items have been resolved by the architecture improvement work (`.workmemo/5_review/baafoo_architecture_improvement_todo.md`):
- ✅ Fixed (P0-6): `RouteManager.rebuildRouteTable` now uses atomic reference swap instead of clear+putAll
- ✅ Fixed (P0-5): `PassthroughProxy` SSL verification is now secure by default; `sslVerifyDisabled` must be explicitly set to `true` to disable
- ✅ Fixed (P0): `TcpStubHandler` no longer blocks the Netty EventLoop — delay is now scheduled via `ctx.executor().schedule(...)` instead of `Thread.sleep`
- ✅ Fixed: Multi-charset support — `Rule.requestCharset` field added for request-side GBK/GB2312/Big5 decoding; `ResponseEntry.charset` now respected by Kafka/Pulsar/JMS stub responses (was dead field); HTTP request body decoded from `Content-Type` header charset; JMS preset messages use `BytesMessage` + `BaafooCharset` property for non-UTF-8
