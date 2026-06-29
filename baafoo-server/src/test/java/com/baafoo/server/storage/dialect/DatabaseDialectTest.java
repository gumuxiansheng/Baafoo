package com.baafoo.server.storage.dialect;

import org.junit.Test;
import static org.junit.Assert.*;

public class DatabaseDialectTest {

    @Test
    public void fromConfigReturnsH2ForNull() {
        assertEquals(DatabaseDialect.H2, DatabaseDialect.fromConfig(null));
    }

    @Test
    public void fromConfigReturnsH2ForEmpty() {
        assertEquals(DatabaseDialect.H2, DatabaseDialect.fromConfig(""));
    }

    @Test
    public void fromConfigReturnsH2ForH2() {
        assertEquals(DatabaseDialect.H2, DatabaseDialect.fromConfig("h2"));
    }

    @Test
    public void fromConfigReturnsH2ForH2Upper() {
        assertEquals(DatabaseDialect.H2, DatabaseDialect.fromConfig("H2"));
    }

    @Test
    public void fromConfigReturnsPostgresql() {
        assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.fromConfig("postgresql"));
    }

    @Test
    public void fromConfigReturnsPostgresqlForUpper() {
        assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.fromConfig("POSTGRESQL"));
    }

    @Test
    public void fromConfigReturnsH2ForUnknown() {
        assertEquals(DatabaseDialect.H2, DatabaseDialect.fromConfig("mysql"));
    }

    @Test
    public void configValueH2() {
        assertEquals("h2", DatabaseDialect.H2.getConfigValue());
    }

    @Test
    public void configValuePostgresql() {
        assertEquals("postgresql", DatabaseDialect.POSTGRESQL.getConfigValue());
    }

    @Test
    public void databaseIdH2() {
        assertEquals("h2", DatabaseDialect.H2.getDatabaseId());
    }

    @Test
    public void databaseIdPostgresql() {
        assertEquals("postgresql", DatabaseDialect.POSTGRESQL.getDatabaseId());
    }

    @Test
    public void driverClassNameH2() {
        assertEquals("org.h2.Driver", DatabaseDialect.H2.getDriverClassName());
    }

    @Test
    public void driverClassNamePostgresql() {
        assertEquals("org.postgresql.Driver", DatabaseDialect.POSTGRESQL.getDriverClassName());
    }

    @Test
    public void buildDefaultJdbcUrlH2() {
        String url = DatabaseDialect.H2.buildDefaultJdbcUrl("/data/baafoo");
        assertTrue(url.startsWith("jdbc:h2:file:"));
        assertTrue(url.contains("/data/baafoo"));
        assertTrue(url.contains("DB_CLOSE_DELAY=-1"));
    }

    @Test
    public void buildDefaultJdbcUrlPostgresql() {
        String url = DatabaseDialect.POSTGRESQL.buildDefaultJdbcUrl("/data");
        assertEquals("jdbc:postgresql://localhost:5432/baafoo", url);
    }

    @Test
    public void autoIncrementTypeH2() {
        assertEquals("BIGINT AUTO_INCREMENT", DatabaseDialect.H2.getAutoIncrementType());
    }

    @Test
    public void autoIncrementTypePostgresql() {
        assertEquals("BIGSERIAL", DatabaseDialect.POSTGRESQL.getAutoIncrementType());
    }

    @Test
    public void supportsAlterColumnIfNotExistsH2() {
        assertTrue(DatabaseDialect.H2.supportsAlterColumnIfNotExists());
    }

    @Test
    public void supportsAlterColumnIfNotExistsPostgresql() {
        assertFalse(DatabaseDialect.POSTGRESQL.supportsAlterColumnIfNotExists());
    }

    @Test
    public void getUpsertSqlH2() {
        String sql = DatabaseDialect.H2.getUpsertSql("rules", "id", "id,name", "?,?", "name=EXCLUDED.name");
        assertEquals("MERGE INTO rules (id,name) KEY(id) VALUES (?,?)", sql);
    }

    @Test
    public void getUpsertSqlPostgresql() {
        String sql = DatabaseDialect.POSTGRESQL.getUpsertSql("rules", "id", "id,name", "?,?", "name=EXCLUDED.name");
        assertEquals("INSERT INTO rules (id,name) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name", sql);
    }
}
