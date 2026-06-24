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
| `baafoo-plugin-api` | `AgentPlugin` SPI | **zero external deps** (must load from Bootstrap CL) |
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
- Server ports: 8084 (API+Web console), 9000–9005 (HTTP/TCP/Kafka/Pulsar/JMS/gRPC stub)
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
  deploy/staging/       # Agent & Server config for Docker staging env
  test-rules/           # 31 JSON rule files covering all protocols, condition types, operators, and environment modes
    rules/              # Individual rule JSON files
    register-rules.ps1  # PowerShell script to register all rules via API
    register-all.sh     # Bash equivalent
  tmp/                  # Scratch dir for test runs (gitignored, never commit)
  TEST-MANUAL.md        # Full test manual (architecture, matrix, steps, acceptance criteria)
  TEST-REPORT.md        # Latest test report
  test-fullchain.ps1    # Full-chain integration test (48 test cases, Docker-based)
  test-integration.ps1  # End-to-end integration test script
  fix-env.sql           # SQL fix for environment data
```

### Test Categories (48 cases in test-fullchain.ps1)

| Category | IDs | Description |
|----------|-----|-------------|
| F: Framework | F01–F04 | Health check, DB, rule registration, environment setup |
| H: HTTP | H01–H09 | GET/POST stub, error code, delay, proxy, GraphQL, Feign plugin |
| G: gRPC | G01–G04 | gRPC Unary stub, service/method matching, grpc-status, delay |
| T: TCP | T01–T03 | TCP stub, NIO (skip), multi-round |
| K: Kafka | K01–K03 | Kafka produce/consume, header condition |
| P: Pulsar | P01–P03 | Pulsar produce/consume, wildcard topic |
| J: JMS | J01–J02 | JMS produce/consume |
| D: Direction | D01–D03 | MQ direction annotation (Kafka/JMS skip, Pulsar) |
| PL: Plugin | PL01–PL03 | Plugin SPI load, status, Feign functional test |
| E: Environment | E01–E02 | Environment isolation (staging-a vs staging-b) |
| C: Condition Types | C01–C10 | header/query/body/bodyJsonPath/contains/endsWith/regex/exists/caseInsensitive/disabled |
| M: Environment Modes | M01–M05 | STUB/PASSTHROUGH/RECORD/RECORD_AND_STUB/RECORD_ALL |

### Rule File Coverage (34 files)

**Protocol rules (19):** http-get, http-post, http-error, http-delay, http-proxy, http-graphql, http-request-count, grpc-greeter, grpc-delay, grpc-error, tcp-hex, tcp-regex, tcp-multiround, kafka-basic, kafka-header, pulsar-basic, pulsar-wildcard, jms-basic, jms-queue

**Condition type rules (9):** http-header, http-query, http-body, http-jsonpath, http-contains, http-endswith, http-path-regex, http-header-exists, http-caseinsensitive

**Special rules (6):** http-disabled (enabled=false), http-no-env (global rule), http-feign (plugin), kafka-direction, pulsar-direction, jms-direction

**Condition types covered:** method, path, topic, header, query, body, bodyJsonPath, graphqlOperationName, graphqlOperationType, requestCount, grpcService, grpcMethod

**Operators covered:** equals, contains, startsWith, endsWith, regex, exists

**Rule**: All intermediate/temp files generated during test execution (logs, dumps, temp configs, etc.) must be written to `testing/tmp/`. This directory is gitignored. Never write test artifacts to the project root or module directories.

Docker staging environment:

```bash
# Start staging cluster (all services including staging-init)
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build

# Rebuild specific services after code changes
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build server app-env-a
```

Staging environments: `staging-a` (STUB mode), `staging-b` (STUB mode), `staging-c` (fallback). Agent configs include `apiKey: "staging-admin-key"` for server authentication.

## Known Issues

See `.review/deep-code-review-report.md` for 20+ verified findings including:
- P0: `TcpStubHandler` uses `Thread.sleep` on Netty EventLoop
- P0: `RouteManager.rebuildRouteTable` non-atomic clear+putAll
- P0: `PassthroughProxy` skips SSL verification
