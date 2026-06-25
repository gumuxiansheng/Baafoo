package com.baafoo.plugin.service;

/**
 * Unified service context injected into PluginContext.
 *
 * <p>Provides plugins with access to core Baafoo services (rule store,
 * recording store, server admin). The implementation lives in
 * baafoo-server and wraps StorageService.</p>
 *
 * <p><b>Null when running in Agent-only mode</b> (no Server attached).
 * Plugins must null-check before use.</p>
 */
public interface PluginServices {

    /**
     * @return rule store, or null if not available
     */
    RuleStore getRuleStore();

    /**
     * @return recording store, or null if not available
     */
    RecordingStore getRecordingStore();

    /**
     * @return server admin, or null if not available
     */
    ServerAdmin getServerAdmin();
}
