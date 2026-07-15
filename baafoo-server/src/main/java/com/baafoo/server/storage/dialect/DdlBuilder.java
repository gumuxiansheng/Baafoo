package com.baafoo.server.storage.dialect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Database DDL builder that loads and executes dialect-specific SQL scripts
 * from classpath resources ({@code sql/schema-{dialect}.sql}).
 *
 * <p>SQL statements in the script files are separated by semicolons. Each
 * statement is executed individually so that non-fatal errors (e.g. "column
 * already exists" on idempotent ALTER statements) are tolerated without
 * aborting the entire schema initialization.</p>
 */
public class DdlBuilder {

    private static final Logger log = LoggerFactory.getLogger(DdlBuilder.class);

    private final DatabaseDialect dialect;

    public DdlBuilder(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Load and execute the schema SQL script for the configured dialect.
     */
    public void createTablesIfNotExist(Connection conn) throws SQLException {
        String resourcePath = "sql/schema-" + dialect.getConfigValue() + ".sql";
        List<String> statements = loadStatements(resourcePath);
        int executed = 0;
        int skipped = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                try {
                    stmt.executeUpdate(sql);
                    executed++;
                } catch (SQLException e) {
                    if (isTolerableError(e)) {
                        skipped++;
                    } else {
                        throw e;
                    }
                }
            }
        }
        log.info("Schema initialized (dialect: {}, executed: {}, skipped: {})",
                dialect, executed, skipped);
    }

    /**
     * Load SQL statements from a classpath resource.
     * Straps line comments ({@code --}) and blank lines, then splits on semicolons.
     * Semicolons inside dollar-quoted strings (PostgreSQL function bodies) are
     * preserved by tracking the {@code $$ ... $$} quoting state.
     */
    private List<String> loadStatements(String resourcePath) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalStateException("Schema SQL resource not found on classpath: " + resourcePath);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Strip line comments (everything after --)
                int commentIdx = line.indexOf("--");
                if (commentIdx >= 0) {
                    line = line.substring(0, commentIdx);
                }
                line = line.trim();
                if (line.isEmpty()) continue;

                // Track PostgreSQL dollar-quoting ($$ ... $$)
                // so that semicolons inside function bodies don't split statements.
                int searchFrom = 0;
                while (true) {
                    int dqIdx = line.indexOf("$$", searchFrom);
                    if (dqIdx < 0) break;
                    inDollarQuote = !inDollarQuote;
                    searchFrom = dqIdx + 2;
                }

                current.append(line).append(' ');

                if (!inDollarQuote && line.endsWith(";")) {
                    String stmt = current.toString().trim();
                    if (stmt.endsWith(";")) {
                        stmt = stmt.substring(0, stmt.length() - 1).trim();
                    }
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    current.setLength(0);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read schema SQL resource: " + resourcePath, e);
        }
        // Trailing statement without semicolon
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    /**
     * Determine whether a SQLException from an idempotent DDL statement can be
     * safely ignored. This covers:
     * <ul>
     *   <li>"Column already exists" — from ALTER TABLE ADD COLUMN on PostgreSQL</li>
     *   <li>"Duplicate" errors — e.g. duplicate index or trigger names</li>
     *   <li>Column type already matches target — from ALTER COLUMN TYPE</li>
     * </ul>
     */
    private boolean isTolerableError(SQLException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String sqlState = e.getSQLState();
        // H2: "Column ... already exists"
        // PostgreSQL: "column ... of relation ... already exists"
        // PostgreSQL: "relation ... already exists" (index)
        // PostgreSQL: "function ... already exists" (CREATE OR REPLACE should handle it, but just in case)
        // Generic: "already exists", "duplicate"
        return msg.contains("already exists")
                || msg.contains("duplicate")
                || msg.contains("unique index or primary key")  // H2 FT_CREATE_INDEX 重试
                || "42701".equals(sqlState)    // duplicate_column
                || "42P07".equals(sqlState)    // duplicate_table
                || "42710".equals(sqlState)    // duplicate_object
                || "42723".equals(sqlState)    // duplicate_function
                || "23505".equals(sqlState);   // unique_violation (H2 full-text index 重试)
    }
}
