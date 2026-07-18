package com.baafoo.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Chaos engineering profile (PRD §6 R-S13).
 *
 * <p>A Chaos profile is a named, reusable fault injection scenario that can be
 * activated/deactivated via REST API. Each profile targets one or more
 * environments and contains a list of fault injection rules to apply.</p>
 *
 * <p>v2.0 scope (P2):
 * <ul>
 *   <li>YAML/JSON configuration file defining profiles</li>
 *   <li>REST API to activate/deactivate profiles</li>
 *   <li>Emergency stop to clear all active Chaos rules</li>
 * </ul>
 * </p>
 *
 * <p>Not implemented in v2.0:
 * <ul>
 *   <li>Preset industry scenario packs (deferred to v3.0)</li>
 *   <li>Chaos experiment UI (deferred to v3.0)</li>
 *   <li>Experiment result reports with metrics (deferred to v3.0)</li>
 * </ul>
 * </p>
 */
public class ChaosProfile {

    /** Unique profile name (used as identifier in API calls) */
    private String name;

    /** Human-readable description of the scenario */
    private String description;

    /** Target environments where this profile's rules will be active */
    private List<String> environments;

    /** Fault injection rules to apply when this profile is activated */
    private List<ChaosRule> rules;

    /**
     * Priority for generated rules (H6 fix).
     *
     * <p>Lower number = higher priority. Default is 50, which is higher than
     * the standard rule default (100), so chaos rules take precedence over
     * normal stub rules. Configurable per-profile to allow fine-tuning when
     * multiple chaos profiles or rule sets coexist.</p>
     */
    private int priority = 50;

    /** Optional cron expression for scheduled activation (Phase 3) */
    private String schedule;

    public ChaosProfile() {
        this.environments = Collections.emptyList();
        this.rules = Collections.emptyList();
    }

    // --- Getters / Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getEnvironments() { return environments; }
    public void setEnvironments(List<String> environments) { this.environments = environments; }

    public List<ChaosRule> getRules() { return rules; }
    public void setRules(List<ChaosRule> rules) { this.rules = rules; }

    /** H6: priority for generated rules (default 50, lower = higher priority). */
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    @Override
    public String toString() {
        return "ChaosProfile{name='" + name + "', rules=" + (rules != null ? rules.size() : 0) + "}";
    }

    /**
     * A single Chaos rule within a profile.
     *
     * <p>Each rule defines a target (path + method) and a fault injection
     * configuration. When the profile is activated, a Baafoo {@link Rule} is
     * generated for each Chaos rule and saved to the storage.</p>
     */
    public static class ChaosRule {

        /** Rule name (human-readable) */
        private String name;

        /** HTTP method to match (GET, POST, etc.) */
        private String method;

        /** HTTP path to match (supports regex) */
        private String path;

        /** Fault injection configuration to apply */
        private FaultInjection faultInjection;

        public ChaosRule() {
        }

        // --- Getters / Setters ---

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public FaultInjection getFaultInjection() { return faultInjection; }
        public void setFaultInjection(FaultInjection faultInjection) { this.faultInjection = faultInjection; }

        @Override
        public String toString() {
            return "ChaosRule{name='" + name + "', method=" + method + ", path=" + path + "}";
        }
    }
}
