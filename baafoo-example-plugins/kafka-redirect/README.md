## Kafka Redirect Plugin (Baafoo Example)

A minimal example demonstrating how to write a Baafoo agent plugin that intercepts Kafka connections and redirects them to a custom mock broker.

### Features Demonstrated

- `InterceptResult.redirect()` for binary protocol redirection
- Per-plugin configuration via `configure(Map<String, Object>)`
- Topic-based filtering using `PluginContext.getTopic()`
- Graceful fallback via `InterceptResult.passthrough()`

### Build

```bash
cd baafoo-example-plugins/kafka-redirect
mvn clean package
```

### Deploy

```bash
cp target/kafka-redirect-plugin-1.0.0.jar /path/to/baafoo/plugins/
```

### Configure

Add to `baafoo-agent.yml`:

```yaml
plugins:
  configs:
    kafka-redirect:
      redirectHost: "localhost"
      redirectPort: 9050
      excludeTopics:
        - "internal-health"
        - "system-metrics"
```

### Test

```bash
mvn test
```

5 unit tests covering: default redirect, custom config, exclude topics, null topic handling, and plugin metadata.
