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

- `baafoo-agent/pom.xml` relocates `com.fasterxml.jackson`, `org.slf4j`, `com.baafoo.core`, `com.baafoo.plugin`, `org.yaml.snakeyaml` into `com.baafoo.agent.shaded.*`
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
java -jar baafoo-server/target/baafoo-server-1.0.0-SNAPSHOT.jar baafoo-server.yml

# App with Agent
java -javaagent:baafoo-agent/target/baafoo-agent-1.0.0-SNAPSHOT.jar=config=baafoo-agent.yml -jar your-app.jar
```

- Java 9+ requires `--add-opens java.base/java.net=ALL-UNNAMED`
- Server ports: 8084 (API+Web console), 9000–9004 (HTTP/TCP/Kafka/Pulsar/JMS stub)
- All API paths: `/__baafoo__/api/*`
- Server defaults: H2 embedded DB, `unmatchedDefault: passthrough`

## Testing (integration)

Integration test assets live in `testing/`:

```
testing/
  deploy/staging/       # Agent & Server config for Docker staging env
  test-rules/           # 16 JSON rule files covering all protocols (HTTP/Kafka/Pulsar/JMS/TCP)
    rules/              # Individual rule JSON files
    register-rules.ps1  # PowerShell script to register all rules via API
    register-all.sh     # Bash equivalent
  tmp/                  # Scratch dir for test runs (gitignored, never commit)
  TEST-MANUAL.md        # Full test manual (architecture, matrix, steps, acceptance criteria)
  TEST-REPORT.md        # Latest test report (17/17 passed, 100%)
  test-integration.ps1  # End-to-end integration test script
  fix-env.sql           # SQL fix for environment data
```

**Rule**: All intermediate/temp files generated during test execution (logs, dumps, temp configs, etc.) must be written to `testing/tmp/`. This directory is gitignored. Never write test artifacts to the project root or module directories.

Docker staging environment:

```bash
# Start staging cluster
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d

# Rebuild after code changes
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build server app-env-a
```

## Known Issues

See `.review/deep-code-review-report.md` for 20+ verified findings including:
- P0: `TcpStubHandler` uses `Thread.sleep` on Netty EventLoop
- P0: `RouteManager.rebuildRouteTable` non-atomic clear+putAll
- P0: `PassthroughProxy` skips SSL verification
