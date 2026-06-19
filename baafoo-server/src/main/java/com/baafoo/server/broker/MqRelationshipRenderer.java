package com.baafoo.server.broker;

import com.baafoo.core.model.MqRelationship;
import com.baafoo.core.util.TemplateEngine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders MQ relationship key/value templates against a source message context.
 *
 * <p>Supports the standard {@link TemplateEngine} variables such as
 * {@code {{request.body}}}, {@code {{faker.*}}}, etc., plus MQ-specific
 * placeholders {@code {{topic}}}, {@code {{key}}} and {@code {{partition}}}.</p>
 */
final class MqRelationshipRenderer {

    private static final Pattern MQ_PLACEHOLDER = Pattern.compile("\\{\\{\\s*(topic|key|partition)\\s*\\}\\}");

    private MqRelationshipRenderer() {
    }

    /**
     * Render the relationship value template for the given source message.
     */
    static String renderValue(MqRelationship relationship, String topic, String key,
                               int partition, String body, String environment) {
        String template = relationship.getValueTemplate();
        if (template == null || template.isEmpty()) {
            return body;
        }
        return render(template, topic, key, partition, body, environment);
    }

    /**
     * Render the relationship key template for the given source message.
     */
    static String renderKey(MqRelationship relationship, String topic, String key,
                             int partition, String body, String environment) {
        String template = relationship.getKeyTemplate();
        if (template == null || template.isEmpty()) {
            return key;
        }
        return render(template, topic, key, partition, body, environment);
    }

    private static String render(String template, String topic, String key,
                                  int partition, String body, String environment) {
        // First resolve MQ-specific placeholders so they are not passed to TemplateEngine.
        // Use StringBuffer because Matcher.appendReplacement / appendTail only accept
        // StringBuffer on Java 8 (the project's target runtime).
        StringBuffer sb = new StringBuffer();
        Matcher m = MQ_PLACEHOLDER.matcher(template);
        while (m.find()) {
            String name = m.group(1);
            String value;
            if ("topic".equals(name)) {
                value = topic == null ? "" : topic;
            } else if ("key".equals(name)) {
                value = key == null ? "" : key;
            } else if ("partition".equals(name)) {
                value = String.valueOf(partition);
            } else {
                value = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        String preRendered = sb.toString();

        // Then run the standard template engine for request/faker variables.
        if (preRendered.contains("{{")) {
            TemplateEngine.RequestContext ctx = new TemplateEngine.RequestContext(
                    null, topic, null,
                    java.util.Collections.<String, String>emptyMap(),
                    java.util.Collections.<String, String>emptyMap(),
                    body, environment);
            return TemplateEngine.render(preRendered, ctx);
        }
        return preRendered;
    }
}
