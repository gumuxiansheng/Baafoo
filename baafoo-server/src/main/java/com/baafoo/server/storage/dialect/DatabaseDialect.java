package com.baafoo.server.storage.dialect;

/**
 * Supported database dialects.
 * Each dialect knows how to generate DDL and DML statements compatible with the target database.
 */
public enum DatabaseDialect {

    H2("h2"),
    POSTGRESQL("postgresql");

    private final String configValue;

    DatabaseDialect(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    /**
     * Resolve dialect from configuration value.
     *
     * @param value configuration value (e.g. "h2", "postgresql")
     * @return matching dialect, defaults to H2 if null/empty/unrecognized
     */
    public static DatabaseDialect fromConfig(String value) {
        if (value == null || value.isEmpty()) return H2;
        for (DatabaseDialect d : values()) {
            if (d.configValue.equalsIgnoreCase(value)) return d;
        }
        return H2;
    }

    /**
     * Get the MyBatis databaseId for this dialect.
     */
    public String getDatabaseId() {
        return configValue;
    }

    /**
     * Get the JDBC driver class name for this dialect.
     */
    public String getDriverClassName() {
        switch (this) {
            case POSTGRESQL:
                return "org.postgresql.Driver";
            case H2:
            default:
                return "org.h2.Driver";
        }
    }

    /**
     * Build a default JDBC URL for this dialect when no URL is configured.
     */
    public String buildDefaultJdbcUrl(String dataDir) {
        switch (this) {
            case POSTGRESQL:
                return "jdbc:postgresql://localhost:5432/baafoo";
            case H2:
            default:
                return "jdbc:h2:file:" + dataDir + "/baafoo;DB_CLOSE_DELAY=-1";
        }
    }

    /**
     * Get the auto-increment column definition for this dialect.
     */
    public String getAutoIncrementType() {
        switch (this) {
            case POSTGRESQL:
                return "BIGSERIAL";
            case H2:
            default:
                return "BIGINT AUTO_INCREMENT";
        }
    }

    /**
     * Whether this dialect supports IF NOT EXISTS in ALTER TABLE ADD COLUMN.
     */
    public boolean supportsAlterColumnIfNotExists() {
        return this == H2;
    }

    /**
     * Get the merge/upsert SQL pattern for this dialect.
     * For H2: MERGE INTO ... KEY(...) VALUES(...)
     * For PostgreSQL: INSERT ... ON CONFLICT(...) DO UPDATE SET ...
     */
    public String getUpsertSql(String table, String keyColumn, String columns, String valuesPlaceholder, String updateSetClause) {
        switch (this) {
            case POSTGRESQL:
                return "INSERT INTO " + table + " (" + columns + ") VALUES (" + valuesPlaceholder + ") " +
                        "ON CONFLICT(" + keyColumn + ") DO UPDATE SET " + updateSetClause;
            case H2:
            default:
                return "MERGE INTO " + table + " (" + columns + ") KEY(" + keyColumn + ") VALUES (" + valuesPlaceholder + ")";
        }
    }
}
