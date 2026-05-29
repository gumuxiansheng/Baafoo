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

/**
 * File-based storage for rules, environments, scenes, and recordings.
 *
 * <p>Uses JSON files for persistence. Thread-safe via ConcurrentHashMap
 * for in-memory cache with periodic file sync.</p>
 */
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    private final ServerConfig config;
    private final ObjectMapper mapper;

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
        new File(config.getDataDir()).mkdirs();
        new File(config.getRulesDir()).mkdirs();
        new File(config.getRecordingsDir()).mkdirs();

        loadRules();
        loadEnvironments();
        log.info("Storage initialized: rules={}, environments={}",
                rules.size(), environments.size());
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
    }

    public Rule updateRule(String id, Rule update) {
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
    }

    public boolean deleteRule(String id) {
        if (rules.remove(id) != null) {
            saveRules();
            return true;
        }
        return false;
    }

    public boolean undoRule(String id) {
        List<Rule> history = ruleHistory.get(id);
        if (history == null || history.isEmpty()) {
            return false;
        }
        Rule previous = history.remove(history.size() - 1);
        rules.put(id, previous);
        saveRules();
        return true;
    }

    // --- Environment CRUD ---

    public List<Environment> listEnvironments() {
        return new ArrayList<Environment>(environments.values());
    }

    public Environment getEnvironment(String id) {
        return environments.get(id);
    }

    public Environment getEnvironmentByName(String name) {
        for (Environment env : environments.values()) {
            if (env.getName().equalsIgnoreCase(name)) return env;
        }
        return null;
    }

    public Environment createEnvironment(Environment env) {
        if (env.getId() == null || env.getId().isEmpty()) {
            env.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        env.setCreatedAt(now);
        env.setUpdatedAt(now);

        environments.put(env.getId(), env);
        saveEnvironments();
        return env;
    }

    public Environment updateEnvironment(String id, Environment update) {
        Environment existing = environments.get(id);
        if (existing == null) return null;

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getMode() != null) existing.setMode(update.getMode());
        if (update.getVariables() != null) existing.setVariables(update.getVariables());
        if (update.getMetadata() != null) existing.setMetadata(update.getMetadata());
        existing.setUpdatedAt(System.currentTimeMillis());

        saveEnvironments();
        return existing;
    }

    public boolean deleteEnvironment(String id) {
        if (environments.remove(id) != null) {
            saveEnvironments();
            return true;
        }
        return false;
    }

    // --- Scene Set CRUD ---

    public List<SceneSet> listScenes() {
        return new ArrayList<SceneSet>(scenes.values());
    }

    public SceneSet createScene(SceneSet scene) {
        if (scene.getId() == null || scene.getId().isEmpty()) {
            scene.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        scene.setCreatedAt(now);
        scene.setUpdatedAt(now);
        scenes.put(scene.getId(), scene);
        return scene;
    }

    public SceneSet updateScene(String id, SceneSet update) {
        SceneSet existing = scenes.get(id);
        if (existing == null) return null;
        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getItemIds() != null) existing.setItemIds(update.getItemIds());
        existing.setActive(update.isActive());
        existing.setUpdatedAt(System.currentTimeMillis());
        return existing;
    }

    public boolean deleteScene(String id) {
        return scenes.remove(id) != null;
    }

    // --- Rule Set CRUD ---

    public List<RuleSet> listRuleSets() {
        return new ArrayList<RuleSet>(ruleSets.values());
    }

    public RuleSet createRuleSet(RuleSet ruleSet) {
        if (ruleSet.getId() == null || ruleSet.getId().isEmpty()) {
            ruleSet.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        ruleSet.setCreatedAt(now);
        ruleSet.setUpdatedAt(now);
        ruleSets.put(ruleSet.getId(), ruleSet);
        return ruleSet;
    }

    public boolean deleteRuleSet(String id) {
        return ruleSets.remove(id) != null;
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
        for (RecordingEntry r : batch) {
            addRecording(r);
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

    // --- Agent Management ---

    public AgentRegistration registerAgent(String agentId, String environment, String hostname, String version, List<String> protocols) {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = agentId;
        reg.environment = environment;
        reg.hostname = hostname;
        reg.version = version;
        reg.protocols = protocols;
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
    }

    public void agentHeartbeat(String agentId) {
        AgentRegistration reg = agents.get(agentId);
        if (reg != null) {
            reg.lastHeartbeat = System.currentTimeMillis();
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
        public long registeredAt;
        public long lastHeartbeat;
    }
}
