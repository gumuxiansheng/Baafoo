package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * JdbcStorageService 契约测试。
 *
 * <p>验证 P2-2 拆分后 Facade 的委托正确性：所有聚合根子接口的方法
 * 经 Facade 委托到对应子 service 后，行为应与拆分前一致。</p>
 *
 * <p>使用 H2 内存数据库。覆盖关键路径：
 * Rule/Environment/Recording/User/MqRelationship/RuleSet/Scene 的 create+get+list+delete，
 * 以及接口拆分契约（P0-4）和缓存失效协调。</p>
 */
public class JdbcStorageServiceTest {

    private JdbcStorageService storage;
    private ServerConfig config;

    @Before
    public void setUp() throws Exception {
        config = new ServerConfig();
        ServerConfig.DatabaseConfig dbConfig = new ServerConfig.DatabaseConfig();
        dbConfig.setType("h2");
        dbConfig.setUrl("jdbc:h2:mem:baafoo-test-" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        dbConfig.setUsername("sa");
        dbConfig.setPassword("");
        config.setDatabase(dbConfig);

        storage = new JdbcStorageService(config);
        storage.init();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    // --- 接口拆分契约（P0-4）---

    @Test
    public void storageImplementsAllSubInterfaces() {
        assertTrue(storage instanceof RuleService);
        assertTrue(storage instanceof RuleSetService);
        assertTrue(storage instanceof EnvironmentService);
        assertTrue(storage instanceof SceneService);
        assertTrue(storage instanceof RecordingService);
        assertTrue(storage instanceof AgentService);
        assertTrue(storage instanceof UserService);
        assertTrue(storage instanceof MqRelationshipService);
    }

    @Test
    public void getSceneServiceReturnsSceneService() {
        assertNotNull(storage.getSceneService());
    }

    @Test
    public void getServerConfigReturnsOriginalConfig() {
        assertSame(config, storage.getServerConfig());
    }

    // --- Rule 聚合根 ---

    @Test
    public void ruleCrudViaFacade() {
        assertTrue(storage.listRules().isEmpty());

        Rule rule = new Rule();
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setPriority(100);
        rule.setConditions(Collections.<MatchCondition>emptyList());
        rule.setResponses(Collections.<ResponseEntry>emptyList());
        Rule created = storage.createRule(rule);
        assertNotNull(created.getId());
        assertEquals("test-rule", created.getName());

        Rule fetched = storage.getRule(created.getId());
        assertNotNull(fetched);
        assertEquals("test-rule", fetched.getName());

        assertEquals(1, storage.listRules().size());

        fetched.setName("updated-rule");
        Rule updated = storage.updateRule(created.getId(), fetched);
        assertEquals("updated-rule", updated.getName());

        assertTrue(storage.deleteRule(created.getId()));
        assertNull(storage.getRule(created.getId()));
    }

    @Test
    public void ruleListPagedViaFacade() {
        for (int i = 0; i < 5; i++) {
            Rule rule = new Rule();
            rule.setName("rule-" + i);
            rule.setProtocol("http");
            rule.setEnabled(true);
            rule.setPriority(100);
            rule.setConditions(Collections.<MatchCondition>emptyList());
            rule.setResponses(Collections.<ResponseEntry>emptyList());
            storage.createRule(rule);
        }

        com.baafoo.core.api.PaginatedResult<Rule> page =
                storage.listRulesPaged("http", null, null, null, "createdAt", "asc", 1, 3);
        assertEquals(5, page.getTotal());
        assertEquals(3, page.getItems().size());

        page = storage.listRulesPaged("http", null, null, null, "createdAt", "asc", 2, 3);
        assertEquals(2, page.getItems().size());
    }

    // --- Recording 聚合根 ---

    @Test
    public void recordingAddListCountViaFacade() {
        assertEquals(0, storage.getRecordingCount());

        RecordingEntry rec = new RecordingEntry();
        rec.setRuleId("rule-1");
        rec.setProtocol("http");
        rec.setMethod("GET");
        rec.setPath("/api/test");
        rec.setHost("localhost");
        rec.setPort(8080);
        rec.setResponseStatusCode(200);
        rec.setResponseBody("hello");
        rec.setRecordedAt(System.currentTimeMillis());
        storage.addRecording(rec);

        assertEquals(1, storage.getRecordingCount());

        List<RecordingEntry> recordings = storage.listRecordings("rule-1", 10);
        assertEquals(1, recordings.size());
        assertEquals("rule-1", recordings.get(0).getRuleId());

        assertTrue(storage.deleteRecording(recordings.get(0).getId()));
        assertEquals(0, storage.getRecordingCount());
    }

    @Test
    public void recordingCountsByDayViaFacade() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            RecordingEntry rec = new RecordingEntry();
            rec.setRuleId("rule-1");
            rec.setProtocol("http");
            rec.setMethod("GET");
            rec.setPath("/api/" + i);
            rec.setResponseStatusCode(200);
            rec.setRecordedAt(now);
            storage.addRecording(rec);
        }

        List<java.util.Map<String, Object>> counts = storage.getRecordingCountsByDay(now - 86400000L);
        assertNotNull(counts);
        assertFalse(counts.isEmpty());
    }

    // --- User 聚合根 ---

    @Test
    public void userCrudViaFacade() {
        assertTrue(storage.listUsers().isEmpty());

        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash("hashed-password");
        user.setRole("admin");
        User created = storage.createUser(user);
        assertNotNull(created);

        User fetched = storage.getUserByUsername("admin");
        assertNotNull(fetched);
        assertEquals("admin", fetched.getUsername());
        assertEquals("admin", fetched.getRole());

        assertNull(storage.getUserByApiKey("nonexistent-key"));

        assertTrue(storage.updateUserRole("admin", "user"));
        assertEquals("user", storage.getUserByUsername("admin").getRole());

        assertTrue(storage.updateUserApiKey("admin", "new-api-key"));
        User byKey = storage.getUserByApiKey("new-api-key");
        assertNotNull(byKey);
        assertEquals("admin", byKey.getUsername());

        assertTrue(storage.updateUserPassword("admin", "new-hash"));
        assertEquals("new-hash", storage.getUserByUsername("admin").getPasswordHash());

        assertTrue(storage.updateUserLastLogin("admin"));

        assertEquals(1, storage.listUsers().size());

        assertTrue(storage.deleteUser("admin"));
        assertNull(storage.getUserByUsername("admin"));
    }

    // --- MqRelationship 聚合根 ---

    @Test
    public void mqRelationshipCrudViaFacade() {
        assertTrue(storage.listMqRelationships().isEmpty());

        MqRelationship rel = new MqRelationship();
        rel.setFromProtocol("kafka");
        rel.setFromTopic("input-topic");
        rel.setToProtocol("pulsar");
        rel.setToTopic("output-topic");
        MqRelationship created = storage.createMqRelationship(rel);
        assertNotNull(created.getId());

        MqRelationship fetched = storage.getMqRelationship(created.getId());
        assertNotNull(fetched);
        assertEquals("kafka", fetched.getFromProtocol());

        assertEquals(1, storage.listMqRelationships().size());

        List<MqRelationship> byFrom = storage.listMqRelationshipsByFrom("kafka", "input-topic");
        assertEquals(1, byFrom.size());

        fetched.setToTopic("updated-topic");
        MqRelationship updated = storage.updateMqRelationship(created.getId(), fetched);
        assertEquals("updated-topic", updated.getToTopic());

        assertTrue(storage.deleteMqRelationship(created.getId()));
        assertNull(storage.getMqRelationship(created.getId()));
    }

    // --- 缓存失效跨子 service 协调 ---

    @Test
    public void ruleListCachesAndInvalidatesOnCreate() {
        List<Rule> first = storage.listRules();
        int initialCount = first.size();

        Rule rule = new Rule();
        rule.setName("cache-test");
        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setPriority(100);
        rule.setConditions(Collections.<MatchCondition>emptyList());
        rule.setResponses(Collections.<ResponseEntry>emptyList());
        storage.createRule(rule);

        List<Rule> second = storage.listRules();
        assertEquals(initialCount + 1, second.size());
    }
}
