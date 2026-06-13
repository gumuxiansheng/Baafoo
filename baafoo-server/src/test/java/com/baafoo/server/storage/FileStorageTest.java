package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class FileStorageTest {

    private FileStorage storage;
    private ServerConfig config;

    @Before
    public void setUp() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "baafoo-fs-test-" + System.currentTimeMillis());
        config = new ServerConfig();
        config.setDataDir(new File(tempDir, "data").getAbsolutePath());
        config.setRulesDir(new File(tempDir, "data/rules").getAbsolutePath());
        config.setRecordingsDir(new File(tempDir, "data/recordings").getAbsolutePath());

        storage = new FileStorage(config);
        storage.init();
    }

    @After
    public void tearDown() {
        storage = null;
    }

    @Test
    public void testInitCreatesDirs() {
        assertTrue(new File(config.getDataDir()).exists());
        assertTrue(new File(config.getRulesDir()).exists());
        assertTrue(new File(config.getRecordingsDir()).exists());
    }

    @Test
    public void testListRulesEmpty() {
        assertTrue(storage.listRules().isEmpty());
    }

    @Test
    public void testCreateAndGetRule() {
        Rule rule = new Rule();
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setHost("api.test.com");
        rule.setPort(8084);

        Rule created = storage.createRule(rule);
        assertNotNull(created.getId());
        assertEquals("test-rule", created.getName());
        assertEquals(1, created.getVersion());
        assertTrue(created.getCreatedAt() > 0);
        assertTrue(created.getUpdatedAt() > 0);

        Rule fetched = storage.getRule(created.getId());
        assertNotNull(fetched);
        assertEquals("test-rule", fetched.getName());
    }

    @Test
    public void testCreateRuleGeneratesId() {
        Rule rule = new Rule();
        rule.setName("no-id");
        Rule created = storage.createRule(rule);
        assertNotNull(created.getId());
    }

    @Test
    public void testUpdateRule() {
        Rule rule = new Rule();
        rule.setName("original");
        Rule created = storage.createRule(rule);

        Rule update = new Rule();
        update.setName("updated");
        update.setEnabled(false);
        Rule updated = storage.updateRule(created.getId(), update);

        assertNotNull(updated);
        assertEquals("updated", updated.getName());
        assertFalse(updated.isEnabled());
        assertEquals(2, updated.getVersion());
    }

    @Test
    public void testUpdateNonExistentRule() {
        Rule updated = storage.updateRule("nonexistent", new Rule());
        assertNull(updated);
    }

    @Test
    public void testDeleteRule() {
        Rule rule = new Rule();
        rule.setName("to-delete");
        Rule created = storage.createRule(rule);

        assertTrue(storage.deleteRule(created.getId()));
        assertNull(storage.getRule(created.getId()));
    }

    @Test
    public void testDeleteNonExistentRule() {
        assertFalse(storage.deleteRule("nonexistent"));
    }

    @Test
    public void testUndoRule() {
        Rule rule = new Rule();
        rule.setName("v1");
        Rule created = storage.createRule(rule);

        Rule update = new Rule();
        update.setName("v2");
        storage.updateRule(created.getId(), update);

        assertTrue(storage.undoRule(created.getId()));
        Rule undone = storage.getRule(created.getId());
        assertEquals("v2", undone.getName());
    }

    @Test
    public void testUndoRuleNoHistory() {
        assertFalse(storage.undoRule("nonexistent"));
    }

    @Test
    public void testCreateEnvironment() {
        Environment env = new Environment();
        env.setName("dev");
        env.setMode(EnvironmentMode.STUB);

        Environment created = storage.createEnvironment(env);
        assertNotNull(created.getId());
        assertEquals("dev", created.getName());

        Environment fetched = storage.getEnvironment(created.getId());
        assertNotNull(fetched);
    }

    @Test
    public void testGetEnvironmentByName() {
        Environment env = new Environment();
        env.setName("staging");
        storage.createEnvironment(env);

        Environment found = storage.getEnvironmentByName("staging");
        assertNotNull(found);
        assertNull(storage.getEnvironmentByName("nonexistent"));
    }

    @Test
    public void testListEnvironments() {
        Environment env1 = new Environment();
        env1.setName("dev");
        storage.createEnvironment(env1);

        Environment env2 = new Environment();
        env2.setName("prod");
        storage.createEnvironment(env2);

        assertEquals(2, storage.listEnvironments().size());
    }

    @Test
    public void testUpdateEnvironment() {
        Environment env = new Environment();
        env.setName("old-name");
        Environment created = storage.createEnvironment(env);

        Environment update = new Environment();
        update.setName("new-name");
        Environment updated = storage.updateEnvironment(created.getId(), update);

        assertNotNull(updated);
        assertEquals("new-name", updated.getName());
    }

    @Test
    public void testUpdateNonExistentEnvironment() {
        assertNull(storage.updateEnvironment("nonexistent", new Environment()));
    }

    @Test
    public void testDeleteEnvironment() {
        Environment env = new Environment();
        env.setName("to-delete");
        Environment created = storage.createEnvironment(env);

        assertTrue(storage.deleteEnvironment(created.getId()));
        assertNull(storage.getEnvironment(created.getId()));
    }

    @Test
    public void testDeleteNonExistentEnvironment() {
        assertFalse(storage.deleteEnvironment("nonexistent"));
    }

    @Test
    public void testSceneCRUD() {
        SceneSet scene = new SceneSet();
        scene.setName("smoke-test");
        scene.setActive(true);
        scene.setItemIds(Arrays.asList("r1", "r2"));

        SceneSet created = storage.createScene(scene);
        assertNotNull(created.getId());

        List<SceneSet> scenes = storage.listScenes();
        assertEquals(1, scenes.size());
        assertEquals("smoke-test", scenes.get(0).getName());
    }

    @Test
    public void testUpdateScene() {
        SceneSet scene = new SceneSet();
        scene.setName("original");
        SceneSet created = storage.createScene(scene);

        SceneSet update = new SceneSet();
        update.setName("updated");
        SceneSet updated = storage.updateScene(created.getId(), update);
        assertNotNull(updated);
        assertEquals("updated", updated.getName());
    }

    @Test
    public void testDeleteScene() {
        SceneSet scene = new SceneSet();
        scene.setName("to-delete");
        SceneSet created = storage.createScene(scene);
        assertTrue(storage.deleteScene(created.getId()));
    }

    @Test
    public void testDeleteNonExistentScene() {
        assertFalse(storage.deleteScene("nonexistent"));
    }

    @Test
    public void testRuleSetCRUD() {
        RuleSet rs = new RuleSet();
        rs.setName("my-set");
        rs.setRuleIds(Arrays.asList("r1"));

        RuleSet created = storage.createRuleSet(rs);
        assertNotNull(created.getId());

        assertEquals(1, storage.listRuleSets().size());

        assertTrue(storage.deleteRuleSet(created.getId()));
        assertTrue(storage.listRuleSets().isEmpty());
    }

    @Test
    public void testDeleteNonExistentRuleSet() {
        assertFalse(storage.deleteRuleSet("nonexistent"));
    }

    @Test
    public void testRecording() {
        RecordingEntry rec = new RecordingEntry();
        rec.setRuleId("r1");
        rec.setProtocol("http");
        rec.setResponseBody("response");

        storage.addRecording(rec);
        assertNotNull(rec.getId());
        assertTrue(rec.getRecordedAt() > 0);

        List<RecordingEntry> list = storage.listRecordings("r1", 10);
        assertEquals(1, list.size());

        List<RecordingEntry> allList = storage.listRecordings(null, 10);
        assertEquals(1, allList.size());
    }

    @Test
    public void testDeleteRecording() {
        RecordingEntry rec = new RecordingEntry();
        rec.setRuleId("r1");
        storage.addRecording(rec);

        assertTrue(storage.deleteRecording(rec.getId()));
        assertFalse(storage.deleteRecording("nonexistent"));
    }

    @Test
    public void testAddRecordingsBatch() {
        RecordingEntry r1 = new RecordingEntry();
        r1.setRuleId("r1");
        RecordingEntry r2 = new RecordingEntry();
        r2.setRuleId("r2");

        storage.addRecordings(Arrays.asList(r1, r2));
        assertEquals(2, storage.listRecordings(null, 100).size());
    }

    @Test
    public void testRecordingMaxEntries() {
        for (int i = 0; i < 10010; i++) {
            RecordingEntry rec = new RecordingEntry();
            rec.setRuleId("r" + i);
            storage.addRecording(rec);
        }
        assertTrue(storage.listRecordings(null, 20000).size() <= 10000);
    }

    @Test
    public void testRegisterAgent() {
        FileStorage.AgentRegistration reg = storage.registerAgent("agent-1", "dev", "host1", "1.0", Arrays.asList("http"), "192.168.1.1");
        assertNotNull(reg);
        assertEquals("agent-1", reg.agentId);
    }

    @Test
    public void testAgentHeartbeat() {
        storage.registerAgent("agent-1", "dev", "host1", "1.0", Arrays.asList("http"), "192.168.1.1");
        storage.agentHeartbeat("agent-1", null);

        List<FileStorage.AgentRegistration> agents = storage.listAgents();
        assertEquals(1, agents.size());
    }

    @Test
    public void testAgentHeartbeatUnknown() {
        storage.agentHeartbeat("nonexistent", null);
        assertTrue(storage.listAgents().isEmpty());
    }

    @Test
    public void testListAgents() {
        storage.registerAgent("a1", "dev", "h1", "1.0", Arrays.asList("http"), "192.168.1.1");
        storage.registerAgent("a2", "prod", "h2", "1.0", Arrays.asList("tcp"), "10.0.0.1");

        assertEquals(2, storage.listAgents().size());
        assertEquals(1, storage.getAgentsForEnvironment("dev").size());
    }

    @Test
    public void testRuleSortingByPriority() {
        Rule r1 = new Rule();
        r1.setName("low-priority");
        r1.setPriority(200);
        Rule created1 = storage.createRule(r1);

        Rule r2 = new Rule();
        r2.setName("high-priority");
        r2.setPriority(10);
        Rule created2 = storage.createRule(r2);

        List<Rule> rules = storage.listRules();
        assertEquals(2, rules.size());
        assertEquals(created2.getId(), rules.get(0).getId());
        assertEquals(created1.getId(), rules.get(1).getId());
    }
}
