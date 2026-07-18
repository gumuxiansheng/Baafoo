package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.mapper.RecordingMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC implementation of {@link RecordingService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns recording insert
 * (with periodic trim), paged query, and aggregate statistics. The trim
 * counter ({@link #TRIM_INTERVAL}) is preserved to avoid blocking IO threads
 * on every insert (H8).</p>
 *
 * <p>H-3: {@code trimRecordings} now runs on a dedicated single-thread
 * daemon scheduler instead of inline on the Netty EventLoop. Trim is
 * debounced with an {@link AtomicBoolean} so concurrent inserts only
 * schedule one outstanding trim at a time.</p>
 */
public class JdbcRecordingService extends BaseJdbcService implements RecordingService {

    private static final Logger log = LoggerFactory.getLogger(JdbcRecordingService.class);

    /** H8: trimRecordings is expensive — only run every 50 inserts, not every call. */
    private static final int TRIM_INTERVAL = 50;
    /** L-1: default keepCount for trimRecordings; exported as a named constant. */
    private static final int DEFAULT_TRIM_KEEP = 1000;
    private final AtomicInteger addRecordingCounter = new AtomicInteger(0);
    /** H-3: ensures only one trim is scheduled at a time (debounce). */
    private final AtomicBoolean trimScheduled = new AtomicBoolean(false);
    /** H-3: off-EventLoop trim executor so the IO thread is never blocked. */
    private final ScheduledExecutorService trimExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "baafoo-recording-trim");
        t.setDaemon(true);
        return t;
    });

    public JdbcRecordingService(SqlSessionFactory sqlSessionFactory) {
        super(sqlSessionFactory);
    }

    @Override
    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).listRecordings(ruleId, limit);
        }
    }

    @Override
    public PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, String agentId, String agentIp,
                                                                String protocol, String method, String path,
                                                                Integer statusCode, String keyword,
                                                                int page, int size) {
        try (SqlSession session = openSession()) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            long total = rcm.countRecordings(ruleId, agentId, agentIp, protocol, method, path, statusCode, keyword);
            int offset = (page - 1) * size;
            List<RecordingEntry> items = rcm.listRecordingsPaged(ruleId, agentId, agentIp, protocol, method, path, statusCode, keyword, size, offset);
            return new PaginatedResult<>(page, size, total, items);
        }
    }

    @Override
    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null || recording.getId().isEmpty()) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());
        try (SqlSession session = openSession()) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            rcm.insertRecording(recording);
            // H8: only trim every TRIM_INTERVAL inserts to avoid blocking IO thread.
            // H-3: actually run the trim off the EventLoop via trimExecutor,
            // debounced with trimScheduled so concurrent inserts do not stack
            // multiple trims.
            if (addRecordingCounter.incrementAndGet() % TRIM_INTERVAL == 0) {
                scheduleTrim(rcm);
            }
        } catch (Exception e) {
            log.error("Failed to add recording: {}", e.getMessage());
        }
    }

    @Override
    public void addRecordings(List<RecordingEntry> batch) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            for (RecordingEntry r : batch) {
                if (r.getId() == null || r.getId().isEmpty()) {
                    r.setId(IdGenerator.uuid());
                }
                r.setRecordedAt(System.currentTimeMillis());
                rcm.insertRecording(r);
            }
            session.commit();
            // H8: only trim every TRIM_INTERVAL inserts.
            // H-3: same debounce pattern as addRecording.
            if (addRecordingCounter.addAndGet(batch.size()) % TRIM_INTERVAL == 0) {
                scheduleTrim(rcm);
            }
        } catch (Exception e) {
            log.error("Failed to batch insert recordings: {}", e.getMessage());
        }
    }

    /**
     * H-3: schedule a trim off the EventLoop. Uses CAS on {@link #trimScheduled}
     * so that when many inserts hit the TRIM_INTERVAL boundary concurrently,
     * only one trim is actually queued; subsequent requests are dropped on the
     * floor (the queued trim will reclaim enough rows).
     *
     * <p>The {@code rcm} passed in is from the caller's {@link SqlSession} and
     * would be closed by the time the trim runs, so we open a fresh session
     * inside the async task.</p>
     */
    private void scheduleTrim(RecordingMapper rcm) {
        if (!trimScheduled.compareAndSet(false, true)) {
            return;
        }
        trimExecutor.submit(() -> {
            try {
                try (SqlSession session = openSession()) {
                    session.getMapper(RecordingMapper.class).trimRecordings(DEFAULT_TRIM_KEEP);
                }
            } catch (Exception e) {
                log.warn("Trim recordings failed: {}", e.getMessage());
            } finally {
                trimScheduled.set(false);
            }
        });
    }

    @Override
    public boolean deleteRecording(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).deleteRecording(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete recording {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * L-2: bulk-delete the {@code limit} oldest recordings in one SQL statement.
     * Replaces the previous list-then-loop pattern that issued N+1 queries.
     */
    @Override
    public int deleteOldestN(int limit) {
        if (limit <= 0) return 0;
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).deleteOldestN(limit);
        } catch (Exception e) {
            log.error("Failed to bulk-delete oldest {} recordings: {}", limit, e.getMessage());
            return 0;
        }
    }

    @Override
    public int deleteRecordingsOlderThan(int retentionDays) {
        long cutoffTime = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).deleteRecordingsOlderThan(cutoffTime);
        } catch (Exception e) {
            log.error("Failed to delete old recordings: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getRecordingCount() {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).countAllRecordings();
        } catch (Exception e) {
            log.error("Failed to count recordings: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getRecordingTotalSizeBytes() {
        // Aggregate the actual body bytes (request + response) from the database
        // instead of estimating with a fixed 2KB-per-recording heuristic.
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).sumAllRecordingBodyBytes();
        } catch (Exception e) {
            log.error("Failed to sum recording body bytes: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Map<String, Object>> getRecordingCountsByDay(long startTime) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).countRecordingsByDay(startTime);
        } catch (Exception e) {
            log.error("Failed to get recording counts by day: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
