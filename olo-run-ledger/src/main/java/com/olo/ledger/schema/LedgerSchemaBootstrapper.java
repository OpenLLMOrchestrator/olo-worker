package com.olo.ledger.schema;

import com.olo.config.OloConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Single responsibility: load and execute the ledger schema script (olo_run, olo_run_node, olo_config).
 * Idempotent; safe to call at bootstrap.
 */
public final class LedgerSchemaBootstrapper {

    private static final String SCHEMA_RESOURCE = "schema/olo-ledger.sql";
    private static final Logger log = LoggerFactory.getLogger(LedgerSchemaBootstrapper.class);

    private final OloConfig config;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    public LedgerSchemaBootstrapper(OloConfig config) {
        this.config = config != null ? config : throwNPE();
    }

    private static OloConfig throwNPE() {
        throw new NullPointerException("OloConfig");
    }

    /**
     * Creates ledger tables and indexes if they do not exist. Idempotent; safe to call at bootstrap.
     * Loads and executes schema/olo-ledger.sql from classpath.
     */
    public void ensureSchema(LedgerSchemaBootstrapper.ConnectionProvider connectionProvider) {
        if (!schemaInitialized.compareAndSet(false, true)) {
            log.debug("Ledger schema already initialized; skipping");
            return;
        }
        log.info("Ledger schema: loading script from classpath {}", SCHEMA_RESOURCE);
        String sql = loadSchemaScript();
        String[] statements = sql.split(";");
        int total = 0;
        for (String raw : statements) {
            if (raw.replaceAll("(?m)^\\s*--[^\n]*\n?", "").trim().isEmpty()) continue;
            total++;
        }
        log.info("Ledger schema: connecting to DB {}:{}/{} and executing {} statement(s)", config.getDbHost(), config.getDbPort(), config.getDbName(), total);
        try (Connection c = connectionProvider.getConnection(); Statement st = c.createStatement()) {
            int index = 0;
            for (String raw : statements) {
                String stmt = raw.replaceAll("(?m)^\\s*--[^\n]*\n?", "").trim();
                if (stmt.isEmpty()) continue;
                index++;
                String preview = stmt.length() > 60 ? stmt.substring(0, 60) + "..." : stmt;
                log.info("Ledger schema: executing statement {}/{}: {}", index, total, preview);
                try {
                    st.execute(stmt);
                } catch (SQLException e) {
                    log.error("Ledger schema: statement {}/{} failed. SQL: {} | Error: {} | SQLState: {}", index, total, preview, e.getMessage(), e.getSQLState(), e);
                    throw new RuntimeException("Ledger schema execution failed at statement " + index + ": " + e.getMessage(), e);
                }
            }
            log.info("Ledger schema: all {} statement(s) executed successfully; tables olo_run, olo_run_node are ready", total);
        } catch (SQLException e) {
            log.error("Ledger schema: connection or execution failed. DB={}:{}/{} error={} SQLState={}", config.getDbHost(), config.getDbPort(), config.getDbName(), e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger schema execution failed: " + e.getMessage(), e);
        }
    }

    private String loadSchemaScript() {
        try (var in = LedgerSchemaBootstrapper.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                log.error("Ledger schema resource not found: {}. Tables olo_run, olo_run_node must exist already or schema will not be created.", SCHEMA_RESOURCE);
                return "";
            }
            String sql = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
            log.info("Ledger schema: loaded {} characters from {}", sql.length(), SCHEMA_RESOURCE);
            return sql;
        } catch (Exception e) {
            log.error("Ledger schema load failed: resource={}, error={}", SCHEMA_RESOURCE, e.getMessage(), e);
            throw new RuntimeException("Ledger schema load failed: " + e.getMessage(), e);
        }
    }

    /** Abstraction for obtaining a connection (allows reuse from JdbcLedgerStore). */
    @FunctionalInterface
    public interface ConnectionProvider {
        Connection getConnection() throws SQLException;
    }
}
