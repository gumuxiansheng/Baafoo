package com.baafoo.server.storage;

import com.baafoo.core.model.SceneSet;

import java.util.List;

/**
 * Service for scene-set (rule-set bundle) management.
 *
 * <p>P1-3: extracted from {@link StorageService} to reduce the surface area
 * of {@code JdbcStorageService} (which previously handled rules, environments,
 * scenes, MQ relationships, recordings, agents, and users in a single 900-line
 * class). Scene management has bidirectional coupling with rule writes
 * (scene environments propagate onto member rules; rule updates merge
 * scene-inherited environments), so {@link SceneService} exposes the read-side
 * helper {@link #getInheritedEnvironments(String)} for the rule-update path.</p>
 *
 * <p>Callers that only touch scenes (e.g., {@code SceneApiHandler},
 * {@code SceneTools}) should depend on this interface rather than the
 * broader {@link StorageService}. Callers that touch both scenes and rules
 * (e.g., {@code RuleApiHandler}) may depend on both services.</p>
 */
public interface SceneService {

    // --- Scene CRUD ---

    List<SceneSet> listScenes();

    SceneSet getScene(String id);

    SceneSet createScene(SceneSet scene);

    SceneSet updateScene(String id, SceneSet update);

    boolean deleteScene(String id);

    // --- Rule-Scene coupling (read side) ---

    /**
     * Compute the set of environments inherited by {@code ruleId} from active
     * scenes that include this rule in their {@code itemIds}.
     *
     * <p>Used by the rule-update path to preserve scene-inherited environments
     * when a rule is updated directly (not via a scene). Replaces the duplicate
     * {@code ApiUtils.getInheritedEnvironments} helper.</p>
     *
     * @param ruleId the rule ID
     * @return a list of inherited environment names (never null, possibly empty)
     */
    List<String> getInheritedEnvironments(String ruleId);
}
