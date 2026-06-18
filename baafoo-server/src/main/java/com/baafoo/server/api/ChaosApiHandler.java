package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.ChaosManager;
import com.baafoo.core.util.ChaosManager.ActivateResult;
import com.baafoo.core.util.ChaosManager.DeactivateResult;
import com.baafoo.core.util.ChaosManager.EmergencyStopResult;
import com.baafoo.core.util.ChaosManager.ProfileStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API handler for Chaos engineering endpoints (PRD §6 R-S13).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/chaos/profiles/activate} — activate a Chaos profile</li>
 *   <li>{@code POST /api/chaos/profiles/deactivate} — deactivate a Chaos profile</li>
 *   <li>{@code GET /api/chaos/profiles/status} — list all profiles with active status</li>
 *   <li>{@code POST /api/chaos/emergency-stop} — emergency stop all active profiles</li>
 * </ul>
 * </p>
 *
 * <p>The {@link ChaosManager} is a singleton shared across requests. Profile
 * registration is currently done programmatically (e.g. via config loading at
 * startup); future versions may add a registration API.</p>
 */
class ChaosApiHandler implements ResourceHandler {

    private final ChaosManager chaosManager;

    ChaosApiHandler(ChaosManager chaosManager) {
        this.chaosManager = chaosManager;
    }

    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        // POST /api/chaos/profiles/activate
        if (path.equals(API_PREFIX + "chaos/profiles/activate") && "POST".equals(method)) {
            ctx.requirePermission("rule", "create");
            return handleActivate(body, ctx);
        }

        // POST /api/chaos/profiles/deactivate
        if (path.equals(API_PREFIX + "chaos/profiles/deactivate") && "POST".equals(method)) {
            ctx.requirePermission("rule", "delete");
            return handleDeactivate(body, ctx);
        }

        // GET /api/chaos/profiles/status
        if (path.equals(API_PREFIX + "chaos/profiles/status") && "GET".equals(method)) {
            ctx.requirePermission("rule", "read");
            return handleStatus();
        }

        // POST /api/chaos/emergency-stop
        if (path.equals(API_PREFIX + "chaos/emergency-stop") && "POST".equals(method)) {
            ctx.requirePermission("rule", "delete");
            return handleEmergencyStop(ctx);
        }

        return null;
    }

    /**
     * Activate a Chaos profile.
     *
     * <p>Request body: {@code {"profileName": "my-scenario"}}</p>
     * <p>The generated rules are persisted to storage. If persistence fails
     * mid-loop, the in-memory active state is rolled back via
     * {@link ChaosManager#deactivate(String)} to keep memory and storage
     * consistent (S10 fix).</p>
     */
    private Object handleActivate(String body, ApiContext ctx) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = ctx.mapper.readValue(body, Map.class);
        String profileName = (String) request.get("profileName");
        if (profileName == null || profileName.trim().isEmpty()) {
            return ApiResponse.badRequest("profileName is required");
        }

        ActivateResult result = chaosManager.activate(profileName);
        if (result.getStatus() == ActivateResult.Status.NOT_FOUND) {
            return ApiResponse.notFound("Chaos profile not found: " + profileName);
        }
        if (result.getStatus() == ActivateResult.Status.ALREADY_ACTIVE) {
            return ApiResponse.badRequest("Chaos profile already active: " + profileName);
        }

        // Persist generated rules to storage with compensation rollback on
        // failure (S10 fix): if any rule fails to persist, roll back the
        // in-memory active state and delete any partially-saved rules.
        List<String> savedRuleIds = new ArrayList<String>();
        try {
            for (Rule rule : result.getGeneratedRules()) {
                Rule existing = ctx.storage.getRule(rule.getId());
                if (existing != null) {
                    ctx.storage.updateRule(rule.getId(), rule);
                } else {
                    ctx.storage.createRule(rule);
                }
                savedRuleIds.add(rule.getId());
            }
        } catch (Exception e) {
            // Compensation: roll back in-memory state and clean up partial saves
            chaosManager.deactivate(profileName);
            for (String ruleId : savedRuleIds) {
                try {
                    ctx.storage.deleteRule(ruleId);
                } catch (Exception cleanupEx) {
                    // best-effort cleanup; log and continue
                }
            }
            throw e;
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("profileName", profileName);
        data.put("generatedRuleCount", result.getGeneratedRules().size());
        data.put("savedRuleIds", savedRuleIds);
        data.put("summary", "Activated Chaos profile '" + profileName + "' with "
                + result.getGeneratedRules().size() + " fault injection rules");
        return ApiResponse.ok(data);
    }

    /**
     * Deactivate a Chaos profile.
     *
     * <p>Request body: {@code {"profileName": "my-scenario"}}</p>
     * <p>The generated rules are deleted from storage.</p>
     */
    private Object handleDeactivate(String body, ApiContext ctx) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = ctx.mapper.readValue(body, Map.class);
        String profileName = (String) request.get("profileName");
        if (profileName == null || profileName.trim().isEmpty()) {
            return ApiResponse.badRequest("profileName is required");
        }

        DeactivateResult result = chaosManager.deactivate(profileName);
        if (result.getStatus() == DeactivateResult.Status.NOT_ACTIVE) {
            return ApiResponse.badRequest("Chaos profile is not active: " + profileName);
        }

        // Delete generated rules from storage
        int deletedCount = 0;
        for (String ruleId : result.getRuleIds()) {
            if (ctx.storage.deleteRule(ruleId)) {
                deletedCount++;
            }
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("profileName", profileName);
        data.put("ruleIds", result.getRuleIds());
        data.put("deletedCount", deletedCount);
        data.put("summary", "Deactivated Chaos profile '" + profileName + "', removed "
                + deletedCount + " rules");
        return ApiResponse.ok(data);
    }

    /**
     * Get the status of all Chaos profiles.
     */
    private Object handleStatus() {
        List<ProfileStatus> statuses = chaosManager.getStatus();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("profiles", statuses);
        data.put("activeCount", statuses.stream().filter(s -> s.isActive()).count());
        data.put("totalCount", statuses.size());
        return ApiResponse.ok(data);
    }

    /**
     * Emergency stop: deactivate all active Chaos profiles and delete their rules.
     */
    private Object handleEmergencyStop(ApiContext ctx) throws Exception {
        EmergencyStopResult result = chaosManager.emergencyStop();

        int deletedCount = 0;
        for (String ruleId : result.getRuleIds()) {
            if (ctx.storage.deleteRule(ruleId)) {
                deletedCount++;
            }
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("deactivatedProfiles", result.getDeactivatedProfiles());
        data.put("ruleIds", result.getRuleIds());
        data.put("deletedCount", deletedCount);
        data.put("summary", "Emergency stop: deactivated " + result.getDeactivatedProfiles().size()
                + " profiles, removed " + deletedCount + " rules");
        return ApiResponse.ok(data);
    }
}
