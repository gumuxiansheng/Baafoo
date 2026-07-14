package com.baafoo.server.storage;

import com.baafoo.core.model.RuleSet;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.mapper.RuleMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * JDBC implementation of {@link RuleSetService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns rule-set CRUD
 * (persisted via {@link RuleMapper}). No caching.</p>
 */
public class JdbcRuleSetService extends BaseJdbcService implements RuleSetService {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleSetService.class);

    public JdbcRuleSetService(SqlSessionFactory sqlSessionFactory) {
        super(sqlSessionFactory);
    }

    @Override
    public List<RuleSet> listRuleSets() {
        try (SqlSession session = openSession()) {
            return session.getMapper(RuleMapper.class).listRuleSets();
        }
    }

    @Override
    public RuleSet createRuleSet(RuleSet ruleSet) {
        if (ruleSet.getId() == null || ruleSet.getId().isEmpty()) {
            ruleSet.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        ruleSet.setCreatedAt(now);
        ruleSet.setUpdatedAt(now);

        try (SqlSession session = openSession()) {
            session.getMapper(RuleMapper.class).createRuleSet(ruleSet);
            return ruleSet;
        } catch (Exception e) {
            log.error("Failed to create rule set: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteRuleSet(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RuleMapper.class).deleteRuleSet(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete rule set {}: {}", id, e.getMessage());
            return false;
        }
    }
}
