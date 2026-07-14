package com.baafoo.server.storage;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 共享基类，为各 JdbcXxxService 子服务提供 {@link SqlSessionFactory} 和
 * {@link #openSession()}，避免每个子 service 重复持有同一段样板代码。
 *
 * <p>P0-4：从 {@code JdbcStorageService} 拆分实现类时引入。{@link CacheEntry}
 * 缓存条目（值 + 时间戳原子绑定）也提升到此处供各带缓存的子 service 复用。</p>
 */
public abstract class BaseJdbcService {

    protected final SqlSessionFactory sqlSessionFactory;

    protected BaseJdbcService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    protected SqlSession openSession() {
        return sqlSessionFactory.openSession(true);
    }

    /**
     * Cache value + timestamp bundled in an immutable entry and published
     * atomically via a single {@link java.util.concurrent.atomic.AtomicReference}
     * (Medium 19). Previously the value ({@code rulesCache.set}) and timestamp
     * ({@code rulesCacheTime=}) were updated as two separate volatile writes —
     * readers could observe a fresh value with a stale timestamp and treat the
     * cache as expired, then redundantly reload from the DB.
     */
    protected static final class CacheEntry<T> {
        final T value;
        final long timestamp;

        CacheEntry(T value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
}
