package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.IdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based storage for rules, environments, scenes, and recordings.
 *
 * <p>Uses JSON files for persistence. Thread-safe via ConcurrentHashMap
 * for in-memory cache with ReadWriteLock for file I/O operations.
 * Data directory: ${user.home}/.baafoo/data/</p>
 */
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    private final ServerConfig config;
    private final ObjectMapper mapper;

    /**
     * Per-entity write locks. Replaces the previous single {@link ReadWriteLock}
     * that protected all entities — under that design a write to rules would
     * block writes to environments/scenes/ruleSets even though they touch
     * completely disjoint files (High 10). The split below lets concurrent
     * writes to different entity types proceed in parallel.
     *
     * <p>Note: in-memory reads use {@link ConcurrentHashMap} and do not require
     * these locks; the locks only serialize the disk-persistence step.</p>
     */
    private final ReentrantLock rulesLock = new ReentrantLock();
    private final ReentrantLock environmentsLock = new ReentrantLock();
    private final ReentrantLock scenesLock = new ReentrantLock();
    private final ReentrantLock ruleSetsLock = new ReentrantLock();
    private final ReentrantLock agentsLock = new ReentrantLock();

    /**
     * Dedicated lock for the recordings deque. Kept separate from the
     * entity locks so that high-frequency recording appends (one per stubbed
     * request) do not block writes of rules / environments, and vice versa.
     */
    private final ReentrantLock recordingsLock = new ReentrantLock();

    /** In-memory rule cache (rule ID → rule) */
    private final Map<String, Rule> rules = new ConcurrentHashMap<String, Rule>();

    /** In-memory environments (env ID → env) */
    private final Map<String, Environment> environments = new ConcurrentHashMap<String, Environment>();

    /** In-memory scene sets */
    private final Map<String, SceneSet> scenes = new ConcurrentHashMap<String, SceneSet>();

    /** In-memory rule sets */
    private final Map<String, RuleSet> ruleSets = new ConcurrentHashMap<String, RuleSet>();

    /**
     * Recording entries (newest-first, append-only, periodically flushed).
     *
     * <p>Backed by {@link ArrayDeque} (not {@code CopyOnWriteArrayList}) because:
     * <ul>
     *   <li>recording add is on the hot path (one call per stubbed request) —
     *       {@code add(0, x)} on a COWArrayList copies the whole backing array
     *       each time, making it O(n) per insert and O(n²) over a batch;</li>
     *   <li>{@code deleteRecordingsOlderThan} calls {@code Iterator.remove()},
     *       which COWArrayList does NOT support (throws
     *       {@link UnsupportedOperationException}).</li>
     * </ul>
     * All access is serialized by {@link #recordingsLock}.</p>
     */
    private final Deque<RecordingEntry> recordings = new ArrayDeque<RecordingEntry>();

    /** Agent registrations (agentId → envId) */
    private final Map<String, AgentRegistration> agents = new ConcurrentHashMap<String, AgentRegistration>();

    /** Rule version history (for undo) */
    private final Map<String, List<Rule>> ruleHistory = new ConcurrentHashMap<String, List<Rule>>();

    public FileStorage(ServerConfig config) {
        this.config = config;
        // FileStorage persists rules to disk as pretty-printed JSON for human
        // readability. Copy the shared mapper and enable INDENT_OUTPUT rather
        // than creating a new instance from scratch.
        this.mapper = com.baafoo.core.util.JsonUtils.MAPPER.copy();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Initialize storage - create directories and load existing data.
     * Runs once at startup; acquires each entity lock in turn (no contention
     * expected during bootstrap).
     */
    public void init() throws IOException {
        new File(config.getDataDir()).mkdirs();
        new File(config.getRulesDir()).mkdirs();
        new File(config.getRecordingsDir()).mkdirs();

        rulesLock.lock();
        try {
            loadRules();
        } finally {
            rulesLock.unlock();
        }
        environmentsLock.lock();
        try {
            loadEnvironments();
        } finally {
            environmentsLock.unlock();
        }
        scenesLock.lock();
        try {
            loadScenes();
        } finally {
            scenesLock.unlock();
        }
        ruleSetsLock.lock();
        try {
            loadRuleSets();
        } finally {
            ruleSetsLock.unlock();
        }
        recordingsLock.lock();
        try {
            loadRecordings();
        } finally {
            recordingsLock.unlock();
        }

        log.info("Storage initialized: rules={}, environments={}, scenes={}, ruleSets={}",
                rules.size(), environments.size(), scenes.size(), ruleSets.size());
    }

    // --- Rule CRUD ---

    public List<Rule> listRules() {
        List<Rule> list = new ArrayList<Rule>(rules.values());
        Collections.sort(list, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return Integer.compare(a.getPriority(), b.getPriority());
            }
        });
        return list;
    }

    public Rule getRule(String id) {
        return rules.get(id);
    }

    public Rule createRule(Rule rule) {
        rulesLock.lock();
        try {
            if (rule.getId() == null || rule.getId().isEmpty()) {
                rule.setId(IdGenerator.uuid());
            }
            rule.setVersion(1);
            long now = System.currentTimeMillis();
            rule.setCreatedAt(now);
            rule.setUpdatedAt(now);

            rules.put(rule.getId(), rule);
            saveRules();
            return rule;
        } finally {
            rulesLock.unlock();
        }
    }

    public Rule updateRule(String id, Rule update) {
        rulesLock.lock();
        try {
            Rule existing = rules.get(id);
            if (existing == null) return null;

            // Save to version history for undo
            saveVersion(id, existing);

            // Apply updates
            if (update.getName() != null) existing.setName(update.getName());
            if (update.getProtocol() != null) existing.setProtocol(update.getProtocol());
            if (update.getServiceName() != null) existing.setServiceName(update.getServiceName());
            if (update.getHost() != null) existing.setHost(update.getHost());
            if (update.getPort() != null) existing.setPort(update.getPort());
            if (update.getConditions() != null) existing.setConditions(update.getConditions());
            if (update.getResponses() != null) existing.setResponses(update.getResponses());
            existing.setEnabled(update.isEnabled());
            existing.setPriority(update.getPriority());
            if (update.getTags() != null) existing.setTags(update.getTags());
            existing.setVersion(existing.getVersion() + 1);
            existing.setUpdatedAt(System.currentTimeMillis());

            saveRules();
            return existing;
        } finally {
            rulesLock.unlock();
        }
    }

    public boolean deleteRule(String id) {
        rulesLock.lock();
        try {
            if (rules.remove(id) != null) {
                saveRules();
                // Clean up per-rule counter (Medium 28) — covers all callers
                // (ChaosApiHandler, RuleTools, etc.), not just the RuleApiHandler.
                com.baafoo.core.util.StatefulCounterStore.global().reset(id);
                return true;
            }
            return false;
        } finally {
            rulesLock.unlock();
        }
    }

    public boolean undoRule(String id) {
        rulesLock.lock();
        try {
            List<Rule> history = ruleHistory.get(id);
            if (history == null || history.isEmpty()) {
                return false;
            }
            Rule previous = history.remove(history.size() - 1);
            rules.put(id, previous);
            saveRules();
            return true;
        } finally {
            rulesLock.unlock();
        }
    }

    // --- Environment CRUD ---

    public List<Environment> listEnvironments() {
        List<Environment> list = new ArrayList<Environment>(environments.values());
        list.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
        return list;
    }

    public Environment getEnvironment(String id) {
        return environments.get(id);
    }

    public Environment getEnvironmentByName(String name) {
        if (name == null) return null;
        for (Environment env : environments.values()) {
            if (env.getName().equalsIgnoreCase(name)) return env;
        }
        return null;
    }

    public Environment createEnvironment(Environment env) {
        environmentsLock.lock();
        try {
            if (env.getId() == null || env.getId().isEmpty()) {
                env.setId(IdGenerator.uuid());
            }
            long now = System.currentTimeMillis();
            env.setCreatedAt(now);
            env.setUpdatedAt(now);

            // Backfill agentIds from already-registered agents for this environment
            for (AgentRegistration a : agents.values()) {
                if (env.getName().equals(a.environment) && !env.getAgentIds().contains(a.agentId)) {
                    env.getAgentIds().add(a.agentId);
                }
            }

            environments.put(env.getId(), env);
            saveEnvironments();
            return env;
        } finally {
            environmentsLock.unlock();
        }
    }

    public Environment updateEnvironment(String id, Environment update) {
        environmentsLock.lock();
        try {
            Environment existing = environments.get(id);
            if (existing == null) return null;

            if (update.getName() != null) existing.setName(update.getName());
            if (update.getMode() != null) existing.setMode(update.getMode());
            if (update.getVariables() != null) existing.setVariables(update.getVariables());
            if (update.getMetadata() != null) existing.setMetadata(update.getMetadata());
            existing.setUpdatedAt(System.currentTimeMillis());

            saveEnvironments();
            return existing;
        } finally {
            environmentsLock.unlock();
        }
    }

    public boolean deleteEnvironment(String id) {
        environmentsLock.lock();
        try {
            if (environments.remove(id) != null) {
                saveEnvironments();
                return true;
            }
            return false;
        } finally {
            environmentsLock.unlock();
        }
    }

    // --- Scene Set CRUD ---

    public List<SceneSet> listScenes() {
        List<SceneSet> list = new ArrayList<SceneSet>(scenes.values());
        list.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
        return list;
    }

    public SceneSet createScene(SceneSet scene) {
        scenesLock.lock();
        try {
            if (scene.getId() == null || scene.getId().isEmpty()) {
                scene.setId(IdGenerator.uuid());
            }
            long now = System.currentTimeMillis();
            scene.setCreatedAt(now);
            scene.setUpdatedAt(now);
            scenes.put(scene.getId(), scene);
            saveScenes();
            return scene;
        } finally {
            scenesLock.unlock();
        }
    }

    public SceneSet updateScene(String id, SceneSet update) {
        scenesLock.lock();
        try {
            SceneSet existing = scenes.get(id);
            if (existing == null) return null;
            if (update.getName() != null) existing.setName(update.getName());
            if (update.getDescription() != null) existing.setDescription(update.getDescription());
            if (update.getItemIds() != null) existing.setItemIds(update.getItemIds());
            existing.setActive(update.isActive());
            existing.setUpdatedAt(System.currentTimeMillis());
            saveScenes();
            return existing;
        } finally {
            scenesLock.unlock();
        }
    }

    public boolean deleteScene(String id) {
        scenesLock.lock();
        try {
            if (scenes.remove(id) != null) {
                saveScenes();
                return true;
            }
            return false;
        } finally {
            scenesLock.unlock();
        }
    }

    // --- Rule Set CRUD ---

    public List<RuleSet> listRuleSets() {
        List<RuleSet> list = new ArrayList<RuleSet>(ruleSets.values());
        list.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
        return list;
    }

    public RuleSet createRuleSet(RuleSet ruleSet) {
        ruleSetsLock.lock();
        try {
            if (ruleSet.getId() == null || ruleSet.getId().isEmpty()) {
                ruleSet.setId(IdGenerator.uuid());
            }
            long now = System.currentTimeMillis();
            ruleSet.setCreatedAt(now);
            ruleSet.setUpdatedAt(now);
            ruleSets.put(ruleSet.getId(), ruleSet);
            saveRuleSets();
            return ruleSet;
        } finally {
            ruleSetsLock.unlock();
        }
    }

    public boolean deleteRuleSet(String id) {
        ruleSetsLock.lock();
        try {
            if (ruleSets.remove(id) != null) {
                saveRuleSets();
                return true;
            }
            return false;
        } finally {
            ruleSetsLock.unlock();
        }
    }

    // --- Recording ---

    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        List<RecordingEntry> result = new ArrayList<RecordingEntry>();
        recordingsLock.lock();
        try {
            for (RecordingEntry r : recordings) {
                if (ruleId == null || ruleId.equals(r.getRuleId())) {
                    result.add(r);
                    if (result.size() >= limit) break;
                }
            }
        } finally {
            recordingsLock.unlock();
        }
        return result;
    }

    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());
        recordingsLock.lock();
        try {
            // ArrayDeque.addFirst is O(1) — no array copy (unlike COWArrayList.add(0, ..)).
            recordings.addFirst(recording); // newest first

            // Prune oldest entries beyond the retention cap.
            while (recordings.size() > 10000) {
                recordings.removeLast();
            }
        } finally {
            recordingsLock.unlock();
        }
    }

    public void addRecordings(List<RecordingEntry> batch) {
        recordingsLock.lock();
        try {
            for (RecordingEntry r : batch) {
                if (r.getId() == null) {
                    r.setId(IdGenerator.uuid());
                }
                r.setRecordedAt(System.currentTimeMillis());
                recordings.addFirst(r); // newest first
            }
            // Prune oldest entries beyond the retention cap.
            while (recordings.size() > 10000) {
                recordings.removeLast();
            }
        } finally {
            recordingsLock.unlock();
        }
        // Persist outside the deque lock so reads of recordings are not blocked
        // during the (slow) JSON serialization + disk write.
        saveRecordings();
    }

    public boolean deleteRecording(String id) {
        recordingsLock.lock();
        try {
            // ArrayDeque iterator supports remove() — no array copy on remove
            // (unlike COWArrayList.remove(index) which copies the backing array).
            for (Iterator<RecordingEntry> it = recordings.iterator(); it.hasNext(); ) {
                RecordingEntry r = it.next();
                if (r.getId().equals(id)) {
                    it.remove();
                    return true;
                }
            }
            return false;
        } finally {
            recordingsLock.unlock();
        }
    }

    public int deleteRecordingsOlderThan(int retentionDays) {
        long cutoffTime = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        int deleted = 0;
        recordingsLock.lock();
        try {
            // ArrayDeque iterator supports remove() (COWArrayList's did NOT —
            // this was a latent bug that would throw UnsupportedOperationException).
            Iterator<RecordingEntry> it = recordings.iterator();
            while (it.hasNext()) {
                RecordingEntry r = it.next();
                if (r.getRecordedAt() < cutoffTime) {
                    it.remove();
                    deleted++;
                }
            }
        } finally {
            recordingsLock.unlock();
        }
        return deleted;
    }

    public long getRecordingCount() {
        recordingsLock.lock();
        try {
            return recordings.size();
        } finally {
            recordingsLock.unlock();
        }
    }

    public long getRecordingTotalSizeBytes() {
        long total = 0;
        recordingsLock.lock();
        try {
            for (RecordingEntry r : recordings) {
                if (r.getResponseBody() != null) total += r.getResponseBody().length();
                if (r.getRequestBody() != null) total += r.getRequestBody().length();
            }
        } finally {
            recordingsLock.unlock();
        }
        return total;
    }

    public List<Map<String, Object>> getRecordingCountsByDay(long startTime) {
        Map<Long, Long> dayCounts = new java.util.TreeMap<>();
        recordingsLock.lock();
        try {
            for (RecordingEntry r : recordings) {
                if (r.getRecordedAt() >= startTime) {
                    long day = r.getRecordedAt() / 86400000L * 86400000L;
                    dayCounts.merge(day, 1L, Long::sum);
                }
            }
        } finally {
            recordingsLock.unlock();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map.Entry<Long, Long> entry : dayCounts.entrySet()) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("day", entry.getKey());
            map.put("count", entry.getValue());
            result.add(map);
        }
        return result;
    }

    // --- Agent Management ---

    public AgentRegistration registerAgent(String agentId, String environment, String hostname, String version, List<String> protocols, String agentIp) {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = agentId;
        reg.environment = environment;
        reg.hostname = hostname;
        reg.version = version;
        reg.protocols = protocols;
        reg.agentIp = agentIp;
        reg.registeredAt = System.currentTimeMillis();
        reg.lastHeartbeat = System.currentTimeMillis();

        agentsLock.lock();
        try {
            agents.put(agentId, reg);
        } finally {
            agentsLock.unlock();
        }

        // Associate agent with environment (separate lock — doesn't need to be
        // atomic with the agents.put above).
        Environment env = getEnvironmentByName(environment);
        if (env != null && !env.getAgentIds().contains(agentId)) {
            environmentsLock.lock();
            try {
                if (!env.getAgentIds().contains(agentId)) {
                    env.getAgentIds().add(agentId);
                    saveEnvironments();
                }
            } finally {
                environmentsLock.unlock();
            }
        }

        return reg;
    }

    public void agentHeartbeat(String agentId, String agentIp) {
        AgentRegistration reg = agents.get(agentId);
        if (reg != null) {
            reg.lastHeartbeat = System.currentTimeMillis();
            if (agentIp != null && !agentIp.isEmpty()) {
                reg.agentIp = agentIp;
            }
        }
    }

    public List<AgentRegistration> listAgents() {
        List<AgentRegistration> list = new ArrayList<AgentRegistration>(agents.values());
        list.sort((a, b) -> Long.compare(a.registeredAt, b.registeredAt));
        return list;
    }

    public List<AgentRegistration> getAgentsForEnvironment(String envName) {
        List<AgentRegistration> result = new ArrayList<AgentRegistration>();
        for (AgentRegistration reg : agents.values()) {
            if (reg.environment != null && reg.environment.equals(envName)) {
                result.add(reg);
            }
        }
        return result;
    }

    // --- Persistence ---

    private void saveRules() {
        try {
            File file = new File(config.getRulesDir(), "rules.json");
            mapper.writeValue(file, new ArrayList<Rule>(rules.values()));
        } catch (IOException e) {
            log.error("Failed to save rules: {}", e.getMessage());
        }
    }

    private void loadRules() {
        try {
            File file = new File(config.getRulesDir(), "rules.json");
            if (!file.exists()) return;
            List<Rule> loaded = mapper.readValue(file, new TypeReference<List<Rule>>() {});
            for (Rule r : loaded) {
                rules.put(r.getId(), r);
            }
            log.info("Loaded {} rules", rules.size());
        } catch (IOException e) {
            log.error("Failed to load rules: {}", e.getMessage());
        }
    }

    private void saveEnvironments() {
        try {
            File file = new File(config.getDataDir(), "environments.json");
            mapper.writeValue(file, new ArrayList<Environment>(environments.values()));
        } catch (IOException e) {
            log.error("Failed to save environments: {}", e.getMessage());
        }
    }

    private void loadEnvironments() {
        try {
            File file = new File(config.getDataDir(), "environments.json");
            if (!file.exists()) return;
            List<Environment> loaded = mapper.readValue(file, new TypeReference<List<Environment>>() {});
            for (Environment env : loaded) {
                environments.put(env.getId(), env);
            }
            log.info("Loaded {} environments", environments.size());
        } catch (IOException e) {
            log.error("Failed to load environments: {}", e.getMessage());
        }
    }

    private void saveScenes() {
        try {
            File file = new File(config.getDataDir(), "scenes.json");
            mapper.writeValue(file, new ArrayList<SceneSet>(scenes.values()));
        } catch (IOException e) {
            log.error("Failed to save scenes: {}", e.getMessage());
        }
    }

    private void loadScenes() {
        try {
            File file = new File(config.getDataDir(), "scenes.json");
            if (!file.exists()) return;
            List<SceneSet> loaded = mapper.readValue(file, new TypeReference<List<SceneSet>>() {});
            for (SceneSet s : loaded) {
                scenes.put(s.getId(), s);
            }
            log.info("Loaded {} scenes", scenes.size());
        } catch (IOException e) {
            log.error("Failed to load scenes: {}", e.getMessage());
        }
    }

    private void saveRuleSets() {
        try {
            File file = new File(config.getDataDir(), "rulesets.json");
            mapper.writeValue(file, new ArrayList<RuleSet>(ruleSets.values()));
        } catch (IOException e) {
            log.error("Failed to save rule sets: {}", e.getMessage());
        }
    }

    private void loadRuleSets() {
        try {
            File file = new File(config.getDataDir(), "rulesets.json");
            if (!file.exists()) return;
            List<RuleSet> loaded = mapper.readValue(file, new TypeReference<List<RuleSet>>() {});
            for (RuleSet rs : loaded) {
                ruleSets.put(rs.getId(), rs);
            }
            log.info("Loaded {} rule sets", ruleSets.size());
        } catch (IOException e) {
            log.error("Failed to load rule sets: {}", e.getMessage());
        }
    }

    private void saveRecordings() {
        // Take a snapshot under recordingsLock, then release the lock before
        // performing the (slow) JSON serialization + disk write so that
        // recording appends from other threads are not blocked during I/O.
        List<RecordingEntry> snapshot;
        recordingsLock.lock();
        try {
            snapshot = new ArrayList<RecordingEntry>(recordings);
        } finally {
            recordingsLock.unlock();
        }
        try {
            File file = new File(config.getRecordingsDir(), "recordings.json");
            mapper.writeValue(file, snapshot);
        } catch (IOException e) {
            log.error("Failed to save recordings: {}", e.getMessage());
        }
    }

    private void loadRecordings() {
        try {
            File file = new File(config.getRecordingsDir(), "recordings.json");
            if (!file.exists()) return;
            List<RecordingEntry> loaded = mapper.readValue(file, new TypeReference<List<RecordingEntry>>() {});
            recordingsLock.lock();
            try {
                // File is saved newest-first (matches addFirst semantics).
                // Append each entry to the tail to preserve newest-first order.
                for (RecordingEntry r : loaded) {
                    recordings.addLast(r);
                }
            } finally {
                recordingsLock.unlock();
            }
            log.info("Loaded {} recordings", recordings.size());
        } catch (IOException e) {
            log.error("Failed to load recordings: {}", e.getMessage());
        }
    }

    private void saveVersion(String ruleId, Rule previous) {
        List<Rule> history = ruleHistory.get(ruleId);
        if (history == null) {
            history = new ArrayList<Rule>();
            ruleHistory.put(ruleId, history);
        }
        // Keep max 10 versions
        if (history.size() >= 10) {
            history.remove(0);
        }
        history.add(previous);
    }

    // --- Inner classes ---

    public static class AgentRegistration {
        public String agentId;
        public String environment;
        public String hostname;
        public String version;
        public List<String> protocols;
        public String agentIp;
        public long registeredAt;
        public long lastHeartbeat;
    }
}
