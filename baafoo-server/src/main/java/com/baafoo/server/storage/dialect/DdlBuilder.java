package com.baafoo.server.storage.dialect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database DDL builder that generates dialect-specific CREATE TABLE and index statements.
 */
public class DdlBuilder {

    private static final Logger log = LoggerFactory.getLogger(DdlBuilder.class);

    private final DatabaseDialect dialect;

    public DdlBuilder(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Create all tables and indexes if they do not exist.
     */
    public void createTablesIfNotExist(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            createRulesTable(stmt);
            createRuleHistoryTable(stmt);
            createEnvironmentsTable(stmt);
            createSceneSetsTable(stmt);
            createRuleSetsTable(stmt);
            createRecordingsTable(stmt);
            addColumnIfMissing(stmt, "recordings", "agent_ip", "VARCHAR(45)");
            addColumnIfMissing(stmt, "recordings", "direction", "VARCHAR(20)");
            addColumnIfMissing(stmt, "recordings", "session_id", "VARCHAR(36)");
            addColumnIfMissing(stmt, "recordings", "data_hex", dialect == DatabaseDialect.POSTGRESQL ? "TEXT" : "CLOB");
            addColumnIfMissing(stmt, "recordings", "duration_ms", "BIGINT");
            alterRecordingsPathToVarchar(stmt);
            createAgentsTable(stmt);
            addColumnIfMissing(stmt, "agents", "agent_ip", "VARCHAR(45)");
            createUsersTable(stmt);
            createIndexes(stmt);
            createRecordingsFullTextSearch(stmt);
        }
        log.info("Database tables verified/created (dialect: {})", dialect);
    }

    private void createRulesTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS rules (" +
            "  id VARCHAR(36) PRIMARY KEY," +
            "  name VARCHAR(255)," +
            "  protocol VARCHAR(50)," +
            "  service_name VARCHAR(255)," +
            "  host VARCHAR(255)," +
            "  port INT," +
            "  conditions_json TEXT," +
            "  responses_json TEXT," +
            "  enabled BOOLEAN DEFAULT TRUE," +
            "  priority INT DEFAULT 100," +
            "  tags_json TEXT," +
            "  environments_json TEXT," +
            "  version INT DEFAULT 1," +
            "  created_at BIGINT," +
            "  updated_at BIGINT" +
            ")"
        );
        addColumnIfMissing(stmt, "rules", "environments_json", "TEXT");
        addColumnIfMissing(stmt, "rules", "tcp_rounds_json", "TEXT");
        addColumnIfMissing(stmt, "rules", "tcp_loop", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(stmt, "rules", "tcp_pattern", "VARCHAR(1024)");
        addColumnIfMissing(stmt, "rules", "tcp_prefix_hex", "VARCHAR(1024)");
        addColumnIfMissing(stmt, "rules", "tcp_offset_start", "INT DEFAULT -1");
        addColumnIfMissing(stmt, "rules", "tcp_offset_end", "INT DEFAULT -1");
        addColumnIfMissing(stmt, "rules", "tcp_offset_hex", "VARCHAR(1024)");
        // R-C2 AC-01: rule-level faker seed for deterministic Faker output
        addColumnIfMissing(stmt, "rules", "faker_seed", "BIGINT");
        // R-C2 extension: requestCount auto-reset threshold (stateful mock)
        addColumnIfMissing(stmt, "rules", "request_count_reset", "INT");
        // R-S12: fault injection configuration (stored as JSON)
        addColumnIfMissing(stmt, "rules", "fault_injection_json", "TEXT");
    }

    private void createRuleHistoryTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS rule_history (" +
            "  id " + dialect.getAutoIncrementType() + " PRIMARY KEY," +
            "  rule_id VARCHAR(36) NOT NULL," +
            "  rule_snapshot TEXT NOT NULL," +
            "  created_at BIGINT" +
            ")"
        );
    }

    private void createEnvironmentsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS environments (" +
            "  id VARCHAR(36) PRIMARY KEY," +
            "  name VARCHAR(255) NOT NULL," +
            "  mode VARCHAR(50) DEFAULT 'STUB'," +
            "  agent_ids_json TEXT," +
            "  variables_json TEXT," +
            "  metadata_json TEXT," +
            "  created_at BIGINT," +
            "  updated_at BIGINT" +
            ")"
        );
    }

    private void createSceneSetsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS scene_sets (" +
            "  id VARCHAR(36) PRIMARY KEY," +
            "  name VARCHAR(255)," +
            "  description TEXT," +
            "  item_ids_json TEXT," +
            "  active BOOLEAN DEFAULT FALSE," +
            "  tags_json TEXT," +
            "  environments_json TEXT," +
            "  created_at BIGINT," +
            "  updated_at BIGINT" +
            ")"
        );
        addColumnIfMissing(stmt, "scene_sets", "environments_json", "TEXT");
    }

    private void createRuleSetsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS rule_sets (" +
            "  id VARCHAR(36) PRIMARY KEY," +
            "  name VARCHAR(255)," +
            "  description TEXT," +
            "  rule_ids_json TEXT," +
            "  enabled BOOLEAN DEFAULT TRUE," +
            "  tags_json TEXT," +
            "  created_at BIGINT," +
            "  updated_at BIGINT" +
            ")"
        );
    }

    private void createRecordingsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS recordings (" +
            "  id VARCHAR(36) PRIMARY KEY," +
            "  rule_id VARCHAR(36)," +
            "  environment_id VARCHAR(36)," +
            "  agent_id VARCHAR(36)," +
            "  agent_ip VARCHAR(45)," +
            "  protocol VARCHAR(50)," +
            "  host VARCHAR(255)," +
            "  port INT," +
            "  service_name VARCHAR(255)," +
            "  method VARCHAR(20)," +
            "  path VARCHAR(2000)," +
            "  request_headers_json TEXT," +
            "  request_body TEXT," +
            "  response_status_code INT," +
            "  response_headers_json TEXT," +
            "  response_body TEXT," +
            "  response_time_ms BIGINT," +
            "  recorded_at BIGINT," +
            "  tags_json TEXT" +
            ")"
        );
    }

    /**
     * Migrate existing recordings.path column from TEXT to VARCHAR(2000).
     */
    private void alterRecordingsPathToVarchar(Statement stmt) {
        try {
            if (dialect == DatabaseDialect.POSTGRESQL) {
                stmt.executeUpdate("ALTER TABLE recordings ALTER COLUMN path TYPE VARCHAR(2000)");
            } else {
                stmt.executeUpdate("ALTER TABLE recordings ALTER COLUMN path VARCHAR(2000)");
            }
        } catch (SQLException e) {
            // Column already VARCHAR(2000) or other non-critical error
            log.debug("Path column migration skipped: {}", e.getMessage());
        }
    }

    private void createAgentsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS agents (" +
            "  agent_id VARCHAR(36) PRIMARY KEY," +
            "  environment VARCHAR(255)," +
            "  hostname VARCHAR(255)," +
            "  version VARCHAR(50)," +
            "  protocols_json TEXT," +
            "  agent_ip VARCHAR(45)," +
            "  registered_at BIGINT," +
            "  last_heartbeat BIGINT" +
            ")"
        );
    }

    private void createUsersTable(Statement stmt) throws SQLException {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS users (" +
            "  id VARCHAR(36) PRIMARY KEY," +
            "  username VARCHAR(255) NOT NULL UNIQUE," +
            "  password_hash VARCHAR(512) NOT NULL," +
            "  display_name VARCHAR(255)," +
            "  email VARCHAR(255)," +
            "  role VARCHAR(50) DEFAULT 'guest'," +
            "  api_key VARCHAR(255)," +
            "  created_at BIGINT," +
            "  updated_at BIGINT," +
            "  last_login_at BIGINT" +
            ")"
        );
        addColumnIfMissing(stmt, "users", "display_name", "VARCHAR(255)");
        addColumnIfMissing(stmt, "users", "email", "VARCHAR(255)");
    }

    private void createIndexes(Statement stmt) throws SQLException {
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_protocol ON rules(protocol)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_enabled ON rules(enabled)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_priority ON rules(priority)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_environments_name ON environments(name)");
        // Recordings indexes
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_rule_id ON recordings(rule_id)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_recorded_at ON recordings(recorded_at)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_agent_id ON recordings(agent_id)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_protocol ON recordings(protocol)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_method ON recordings(method)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_status_code ON recordings(response_status_code)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_agent_ip ON recordings(agent_ip)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_path ON recordings(path)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_session_id ON recordings(session_id)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_direction ON recordings(direction)");
        // Other indexes
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agents_environment ON agents(environment)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rule_history_rule_id ON rule_history(rule_id)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_api_key ON users(api_key)");
    }

    /**
     * Set up full-text search for the recordings table.
     * PostgreSQL: tsvector column + GIN index + auto-update trigger.
     * H2: uses LIKE fallback (sufficient for dev/test scale).
     */
    private void createRecordingsFullTextSearch(Statement stmt) {
        if (dialect == DatabaseDialect.POSTGRESQL) {
            createPostgreSQLFullTextSearch(stmt);
        } else if (dialect == DatabaseDialect.H2) {
            createH2FullTextSearch(stmt);
        }
    }

    /**
     * PostgreSQL: tsvector column + GIN index + auto-update trigger.
     */
    private void createPostgreSQLFullTextSearch(Statement stmt) {
        // Add tsvector column
        addColumnIfMissing(stmt, "recordings", "search_vector", "tsvector");

        try {
            // GIN index for full-text search
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_search ON recordings USING GIN(search_vector)");

            // Trigger function to auto-update search_vector on INSERT/UPDATE
            stmt.executeUpdate(
                "CREATE OR REPLACE FUNCTION recordings_search_trigger() RETURNS trigger AS $$\n" +
                "BEGIN\n" +
                "  NEW.search_vector :=\n" +
                "    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.path, '')), 'A') ||\n" +
                "    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.request_headers_json, '')), 'B') ||\n" +
                "    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.response_headers_json, '')), 'B') ||\n" +
                "    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.request_body, '')), 'C') ||\n" +
                "    setweight(to_tsvector('pg_catalog.english', COALESCE(NEW.response_body, '')), 'C');\n" +
                "  RETURN NEW;\n" +
                "END;\n" +
                "$$ LANGUAGE plpgsql"
            );

            // Create trigger (drop first if exists to avoid errors)
            stmt.executeUpdate("DROP TRIGGER IF EXISTS recordings_search_update ON recordings");
            stmt.executeUpdate(
                "CREATE TRIGGER recordings_search_update\n" +
                "  BEFORE INSERT OR UPDATE ON recordings\n" +
                "  FOR EACH ROW EXECUTE FUNCTION recordings_search_trigger()"
            );

            // Populate search_vector for existing rows
            stmt.executeUpdate(
                "UPDATE recordings SET search_vector =\n" +
                "  setweight(to_tsvector('pg_catalog.english', COALESCE(path, '')), 'A') ||\n" +
                "  setweight(to_tsvector('pg_catalog.english', COALESCE(request_headers_json, '')), 'B') ||\n" +
                "  setweight(to_tsvector('pg_catalog.english', COALESCE(response_headers_json, '')), 'B') ||\n" +
                "  setweight(to_tsvector('pg_catalog.english', COALESCE(request_body, '')), 'C') ||\n" +
                "  setweight(to_tsvector('pg_catalog.english', COALESCE(response_body, '')), 'C')\n" +
                "WHERE search_vector IS NULL"
            );

            log.info("PostgreSQL full-text search index created for recordings table");
        } catch (SQLException e) {
            log.warn("Failed to create full-text search for recordings: {}", e.getMessage());
        }
    }

    /**
     * H2: built-in org.h2.fulltext.FullText search index.
     * Uses FT_CREATE_INDEX to index text columns, and FT_SEARCH_DATA for querying.
     */
    private void createH2FullTextSearch(Statement stmt) {
        try {
            // Initialize H2 full-text search (idempotent)
            stmt.executeUpdate("CREATE ALIAS IF NOT EXISTS FT_INIT FOR \"org.h2.fulltext.FullText.init\"");
            stmt.executeUpdate("CALL FT_INIT()");

            // Create full-text index on recordings table (NULL = index all columns)
            // FT_CREATE_INDEX is idempotent if called with same parameters
            stmt.executeUpdate("CALL FT_CREATE_INDEX('PUBLIC', 'RECORDINGS', NULL)");

            log.info("H2 full-text search index created for recordings table");
        } catch (SQLException e) {
            // Already initialized or other non-critical error
            log.debug("H2 full-text search setup: {}", e.getMessage());
        }
    }

    /**
     * Add a column to a table if it does not already exist.
     * Handles dialect differences: H2 supports IF NOT EXISTS, PostgreSQL does not.
     */
    private void addColumnIfMissing(Statement stmt, String table, String column, String type) {
        try {
            if (dialect.supportsAlterColumnIfNotExists()) {
                stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + type);
            } else {
                // For PostgreSQL, try the ALTER and catch the "already exists" error
                stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            }
        } catch (SQLException e) {
            // Column already exists - this is expected
            if (!isColumnExistsError(e)) {
                log.warn("Failed to add column {}.{}: {}", table, column, e.getMessage());
            }
        }
    }

    private boolean isColumnExistsError(SQLException e) {
        String msg = e.getMessage().toLowerCase();
        // H2: "Column ... already exists"
        // PostgreSQL: "column ... of relation ... already exists"
        return msg.contains("already exists") || msg.contains("duplicate") || "42701".equals(e.getSQLState());
    }
}
