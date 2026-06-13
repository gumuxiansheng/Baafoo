# R-S7.6 — Interactive baafoo init

## What was done

Added interactive Q&A mode to the `baafoo init` command.

### 1. Interactive mode (default)
When `baafoo init` is run without `--non-interactive`, the user is prompted:
1. **Downstream services**: "What downstream services does your application depend on?" — comma-separated format like "order-service:8080,payment-service:9090,kafka-cluster:9092"
2. **Protocols**: "Which protocols?" — defaults inferred from service names (kafka→kafka, pulsar→pulsar, jms/mq/queue→jms, others→http)
3. **Server host**: "Server host?" — default "127.0.0.1"
4. **Environment name**: "Environment name?" — default "default"

### 2. Config generation based on answers
- `baafoo-agent.yml`: Uses the specified server host, environment, and protocols
- `baafoo-rules.yml`: Generates mock rules for each specified service with inferred protocols
- `baafoo-server.yml`: Only includes ports for selected protocols

### 3. JVM startup parameter example
After initialization, outputs:
```
JVM startup parameter example:
  java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar

For Java 9+:
  java --add-opens java.base/java.net=ALL-UNNAMED -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar
```

### 4. Overwrite protection
If config files already exist, prompts "Config files already exist. Overwrite? (yes/no)" — aborts if not confirmed.

### 5. Non-interactive mode
`baafoo init --non-interactive` preserves the original behavior with defaults (no prompts).

## Files modified
- `baafoo-cli/src/main/java/com/baafoo/cli/BaafooCli.java` — added interactive mode, service parsing, protocol inference
- `baafoo-cli/src/test/java/com/baafoo/cli/BaafooCliTest.java` — updated test to use `--non-interactive`
