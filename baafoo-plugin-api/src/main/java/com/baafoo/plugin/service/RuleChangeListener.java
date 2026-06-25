package com.baafoo.plugin.service;

/**
 * Listener for rule changes.
 *
 * <p>Plugins implement this to receive notifications when rules are
 * created, updated, or deleted. The notification is delivered via
 * the EventBus as a {@link com.baafoo.plugin.PluginEvent} of type
 * {@code RULE_CHANGED}.</p>
 *
 * <p>Plugins that need programmatic access can also implement this
 * interface and register via {@link ServerAdmin#registerEndpoint}
 * or by returning a listener from {@code AgentPlugin.configure()}.</p>
 *
 * <p>This interface is provided for type-safe rule change handling.
 * It is optional — plugins can also listen to {@code RULE_CHANGED}
 * events via {@link com.baafoo.plugin.AgentPlugin#onEvent}.</p>
 */
public interface RuleChangeListener {

    /**
     * Called when a rule is created, updated, or deleted.
     *
     * @param ruleId the rule ID that changed
     * @param action one of "created", "updated", "deleted"
     * @param environmentId the environment the rule belongs to, or null
     */
    void onRuleChanged(String ruleId, String action, String environmentId);
}
