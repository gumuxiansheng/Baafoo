package com.baafoo.server.storage;

import com.baafoo.core.model.MqRelationship;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.mapper.MqRelationshipMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * JDBC implementation of {@link MqRelationshipService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns MQ relationship
 * CRUD (cross-broker message routing). No caching.</p>
 */
public class JdbcMqRelationshipService extends BaseJdbcService implements MqRelationshipService {

    private static final Logger log = LoggerFactory.getLogger(JdbcMqRelationshipService.class);

    public JdbcMqRelationshipService(SqlSessionFactory sqlSessionFactory) {
        super(sqlSessionFactory);
    }

    @Override
    public List<MqRelationship> listMqRelationships() {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class).listMqRelationships();
        }
    }

    @Override
    public List<MqRelationship> listMqRelationshipsByFrom(String fromProtocol, String fromTopic) {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class)
                    .listMqRelationshipsByFrom(fromProtocol, fromTopic);
        }
    }

    @Override
    public MqRelationship getMqRelationship(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class).getMqRelationship(id);
        }
    }

    @Override
    public MqRelationship createMqRelationship(MqRelationship relationship) {
        if (relationship.getId() == null || relationship.getId().isEmpty()) {
            relationship.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        relationship.setCreatedAt(now);
        relationship.setUpdatedAt(now);

        try (SqlSession session = openSession()) {
            session.getMapper(MqRelationshipMapper.class).createMqRelationship(relationship);
            return relationship;
        } catch (Exception e) {
            log.error("Failed to create MQ relationship: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public MqRelationship updateMqRelationship(String id, MqRelationship update) {
        try (SqlSession session = openSession()) {
            MqRelationshipMapper mapper = session.getMapper(MqRelationshipMapper.class);
            MqRelationship existing = mapper.getMqRelationship(id);
            if (existing == null) return null;

            if (update.getName() != null) existing.setName(update.getName());
            if (update.getFromProtocol() != null) existing.setFromProtocol(update.getFromProtocol());
            if (update.getFromTopic() != null) existing.setFromTopic(update.getFromTopic());
            if (update.getToProtocol() != null) existing.setToProtocol(update.getToProtocol());
            if (update.getToTopic() != null) existing.setToTopic(update.getToTopic());
            if (update.getKeyTemplate() != null) existing.setKeyTemplate(update.getKeyTemplate());
            if (update.getValueTemplate() != null) existing.setValueTemplate(update.getValueTemplate());
            existing.setDelayMs(update.getDelayMs());
            existing.setEnabled(update.isEnabled());
            existing.setUpdatedAt(System.currentTimeMillis());

            mapper.updateMqRelationship(existing);
            return existing;
        } catch (Exception e) {
            log.error("Failed to update MQ relationship {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteMqRelationship(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class).deleteMqRelationship(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete MQ relationship {}: {}", id, e.getMessage());
            return false;
        }
    }
}
