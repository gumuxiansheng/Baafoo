package com.baafoo.plugin;

/**
 * Health status of a loaded plugin.
 *
 * <p>Managed by {@code PluginManager} — plugins do not set their own health.
 * Status transitions:</p>
 * <pre>
 *   UNKNOWN → HEALTHY (first successful intercept)
 *   HEALTHY → DEGRADED (intermittent errors, still serving)
 *   DEGRADED → UNHEALTHY (consecutive failures ≥ threshold, auto-disabled)
 *   UNHEALTHY → HEALTHY (admin re-enables + successful intercept)
 *   any → DISABLED (admin manually disables)
 *   DISABLED → UNKNOWN (admin re-enables)
 * </pre>
 */
public enum PluginHealth {

    /** Just loaded, no intercept() calls yet. */
    UNKNOWN,

    /** Recent intercept() calls all succeeded. */
    HEALTHY,

    /** Some errors but plugin is still serving (fail-closed to default behavior). */
    DEGRADED,

    /** Persistent failures — plugin auto-disabled after consecutive error threshold. */
    UNHEALTHY,

    /** Manually disabled by administrator via REST API. */
    DISABLED
}
