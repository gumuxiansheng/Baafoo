package com.baafoo.plugin.service;

import java.util.List;
import java.util.Map;

/**
 * Read-only access to the rule store.
 *
 * <p>Uses {@code Map<String,Object>} instead of domain models to keep
 * baafoo-plugin-api zero-dependency (Bootstrap CL loadable). Plugins that
 * need strong typing can convert manually.</p>
 *
 * <p>Map keys for rules: id, name, protocol, host, port, serviceName,
 * priority, enabled, environments, conditions (List&lt;Map&gt;),
 * responses (List&lt;Map&gt;).</p>
 */
public interface RuleStore {

    /**
     * List all rules for the given environment.
     *
     * @param environmentId environment ID, or null for all rules
     * @return list of rule maps, empty if none
     */
    List<Map<String, Object>> listRules(String environmentId);

    /**
     * Get a specific rule by ID.
     *
     * @param ruleId rule ID
     * @return rule map, or null if not found
     */
    Map<String, Object> getRule(String ruleId);
}
