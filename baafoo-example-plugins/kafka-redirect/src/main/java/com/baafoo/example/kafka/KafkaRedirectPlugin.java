package com.baafoo.example.kafka;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Example Baafoo plugin: redirects Kafka connections to a custom mock broker,
 * with optional topic-based filtering.
 *
 * <p>Demonstrates:</p>
 * <ul>
 *   <li>Using {@link InterceptResult#redirect} for binary protocol redirection</li>
 *   <li>Reading per-plugin configuration via {@code configure()}</li>
 *   <li>Using protocol-specific fields ({@code topic}) for fine-grained routing</li>
 *   <li>Falling back to {@code passthrough()} for excluded topics</li>
 * </ul>
 *
 * <h3>Configuration (baafoo-agent.yml)</h3>
 * <pre>
 * plugins:
 *   configs:
 *     kafka-redirect:
 *       redirectHost: "localhost"
 *       redirectPort: 9050
 *       excludeTopics:
 *         - "internal-health"
 *         - "system-metrics"
 * </pre>
 */
public class KafkaRedirectPlugin implements AgentPlugin {

    private String redirectHost = "localhost";
    private int redirectPort = 9050;
    private final Set<String> excludeTopics = new HashSet<String>();

    @Override
    public String getName() {
        return "kafka-redirect";
    }

    @Override
    public InterceptTarget getTarget() {
        return InterceptTarget.KAFKA;
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("redirectHost")) {
            this.redirectHost = (String) config.get("redirectHost");
        }
        if (config.containsKey("redirectPort")) {
            this.redirectPort = ((Number) config.get("redirectPort")).intValue();
        }
        if (config.containsKey("excludeTopics")) {
            Object topics = config.get("excludeTopics");
            if (topics instanceof Iterable) {
                for (Object topic : (Iterable<?>) topics) {
                    excludeTopics.add(topic.toString());
                }
            }
        }
    }

    @Override
    public void init() {
        // No heavy initialization needed
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        // Check if the topic is in the exclude list
        String topic = ctx.getTopic();
        if (topic != null && excludeTopics.contains(topic)) {
            return InterceptResult.passthrough();
        }

        // Redirect all other Kafka connections to the mock broker
        return InterceptResult.redirect(redirectHost, redirectPort);
    }

    @Override
    public void destroy() {
        // No resources to clean up
    }
}
