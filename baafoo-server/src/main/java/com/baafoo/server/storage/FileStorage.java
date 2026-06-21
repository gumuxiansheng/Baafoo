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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    /** ReadWriteLock for thread-safe file I/O */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** In-memory rule cache (rule ID → rule) */
    private final Map<String, Rule> rules = new ConcurrentHashMap<String, Rule>();

    /** In-memory environments (env ID → env) */
    private final Map<String, Environment> environments = new ConcurrentHashMap<String, Environment>();

    /** In-memory scene sets */
    private final Map<String, SceneSet> scenes = new ConcurrentHashMap<String, SceneSet>();

    /** In-memory rule sets */
    private final Map<String, RuleSet> ruleSets = new ConcurrentHashMap<String, RuleSet>();

    /** Recording entries (append-only, periodically flushed) */
    private final List<RecordingEntry> recordings = new CopyOnWriteArrayList<RecordingEntry>();

    /** Agent registrations (agentId → envId) */
    private final Map<String, AgentRegistration> agents = new ConcurrentHashMap<String, AgentRegistration>();

    /** Rule version history (for undo) */
    private final Map<String, List<Rule>> ruleHistory = new ConcurrentHashMap<String, List<Rule>>();

    public FileStorage(ServerConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Initialize storage - create directories and load existing data.
     */
    public void init() throws IOException {
        lock.writeLock().lock();
        try {
            new File(config.getDataDir()).mkdirs();
            new File(config.getRulesDir()).mkdirs();
            new File(config.getRecordingsDir()).mkdirs();

            loadRules();
            loadEnvironments();
            loadScenes();
            loadRuleSets();
            loadRecordings();
            log.info("Storage initialized: rules={}, environments={}, scenes={}, ruleSets={}",
                    rules.size(), environments.size(), scenes.size(), ruleSets.size());
        } finally {
            lock.writeLock().unlock();
        }
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
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    public Rule updateRule(String id, Rule update) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    public boolean deleteRule(String id) {
        lock.writeLock().lock();
        try {
            if (rules.remove(id) != null) {
                saveRules();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean undoRule(String id) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    // --- Environment CRUD ---

    public List<Environment> listEnvironments() {
        return new ArrayList<Environment>(environments.values());
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
        lock.writeLock().lock();
        try {
            if (env.getId() == null || env.getId().isEmpty()) {
                env.setId(IdGenerator.uuid());
            }
            long now = System.currentTimeMillis();
            env.setCreatedAt(now);
            env.setUpdatedAt(now);

            environments.put(env.getId(), env);
            saveEnvironments();
            return env;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Environment updateEnvironment(String id, Environment update) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    public boolean deleteEnvironment(String id) {
        lock.writeLock().lock();
        try {
            if (environments.remove(id) != null) {
                saveEnvironments();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --- Scene Set CRUD ---

    public List<SceneSet> listScenes() {
        return new ArrayList<SceneSet>(scenes.values());
    }

    public SceneSet createScene(SceneSet scene) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    public SceneSet updateScene(String id, SceneSet update) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    public boolean deleteScene(String id) {
        lock.writeLock().lock();
        try {
            if (scenes.remove(id) != null) {
                saveScenes();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --- Rule Set CRUD ---

    public List<RuleSet> listRuleSets() {
        return new ArrayList<RuleSet>(ruleSets.values());
    }

    public RuleSet createRuleSet(RuleSet ruleSet) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
    }

    public boolean deleteRuleSet(String id) {
        lock.writeLock().lock();
        try {
            if (ruleSets.remove(id) != null) {
                saveRuleSets();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --- Recording ---

    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        List<RecordingEntry> result = new ArrayList<RecordingEntry>();
        for (RecordingEntry r : recordings) {
            if (ruleId == null || ruleId.equals(r.getRuleId())) {
                result.add(r);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());
        recordings.add(0, recording); // newest first

        // Prune old recordings
        while (recordings.size() > 10000) {
            recordings.remove(recordings.size() - 1);
        }
    }

    public void addRecordings(List<RecordingEntry> batch) {
        lock.writeLock().lock();
        try {
            for (RecordingEntry r : batch) {
                addRecording(r);
            }
            saveRecordings();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteRecording(String id) {
        for (int i = 0; i < recordings.size(); i++) {
            if (recordings.get(i).getId().equals(id)) {
                recordings.remove(i);
                return true;
            }
        }
        return false;
    }

    public int deleteRecordingsOlderThan(int retentionDays) {
        long cutoffTime = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        int deleted = 0;
        java.util.Iterator<RecordingEntry> it = recordings.iterator();
        while (it.hasNext()) {
            RecordingEntry r = it.next();
            if (r.getRecordedAt() < cutoffTime) {
                it.remove();
                deleted++;
            }
        }
        return deleted;
    }

    public long getRecordingCount() {
        return recordings.size();
    }

    public long getRecordingTotalSizeBytes() {
        // Sum actual body byte lengths instead of a fixed 2KB-per-recording estimate.
        long total = 0;
        for (RecordingEntry r : recordings) {
            if (r.getResponseBody() != null) total += r.getResponseBody().length();
            if (r.getRequestBody() != null) total += r.getRequestBody().length();
        }
        return total;
    }

    public List<Map<String, Object>> getRecordingCountsByDay(long startTime) {
        Map<Long, Long> dayCounts = new java.util.TreeMap<>();
        for (RecordingEntry r : recordings) {
            if (r.getRecordedAt() >= startTime) {
                long day = r.getRecordedAt() / 86400000L * 86400000L;
                dayCounts.merge(day, 1L, Long::sum);
            }
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
        lock.writeLock().lock();
        try {
            AgentRegistration reg = new AgentRegistration();
            reg.agentId = agentId;
            reg.environment = environment;
            reg.hostname = hostname;
            reg.version = version;
            reg.protocols = protocols;
            reg.agentIp = agentIp;
            reg.registeredAt = System.currentTimeMillis();
            reg.lastHeartbeat = System.currentTimeMillis();

            agents.put(agentId, reg);

            // Associate agent with environment
            Environment env = getEnvironmentByName(environment);
            if (env != null && !env.getAgentIds().contains(agentId)) {
                env.getAgentIds().add(agentId);
                saveEnvironments();
            }

            return reg;
        } finally {
            lock.writeLock().unlock();
        }
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
        return new ArrayList<AgentRegistration>(agents.values());
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
        try {
            File file = new File(config.getRecordingsDir(), "recordings.json");
            mapper.writeValue(file, new ArrayList<RecordingEntry>(recordings));
        } catch (IOException e) {
            log.error("Failed to save recordings: {}", e.getMessage());
        }
    }

    private void loadRecordings() {
        try {
            File file = new File(config.getRecordingsDir(), "recordings.json");
            if (!file.exists()) return;
            List<RecordingEntry> loaded = mapper.readValue(file, new TypeReference<List<RecordingEntry>>() {});
            for (RecordingEntry r : loaded) {
                recordings.add(r);
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
