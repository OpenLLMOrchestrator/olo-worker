package com.olo.ledger;

import com.olo.config.OloConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * JDBC implementation of LedgerStore. Persists to tables olo_run and olo_run_node.
 * Supports AI cost (MODEL/PLANNER), replay/failure meta, run aggregates, and JSONB where available.
 * Schema (CREATE TABLE IF NOT EXISTS) is executed once at bootstrap via {@link #ensureSchema()}.
 */
public final class JdbcLedgerStore implements LedgerStore {

    private static final String TABLE_RUN = "olo_run";
    private static final String TABLE_NODE = "olo_run_node";
    private static final String TABLE_CONFIG = "olo_config";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String SCHEMA_RESOURCE = "schema/olo-ledger.sql";
    private static final Logger log = LoggerFactory.getLogger(JdbcLedgerStore.class);

    private final OloConfig config;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    public JdbcLedgerStore(OloConfig config) {
        this.config = config != null ? config : throwNPE();
    }

    private static OloConfig throwNPE() {
        throw new NullPointerException("OloConfig");
    }

    /**
     * Creates ledger tables and indexes if they do not exist. Idempotent; safe to call at bootstrap.
     * Loads and executes schema/olo-ledger.sql from classpath.
     */
    public void ensureSchema() {
        if (!schemaInitialized.compareAndSet(false, true)) {
            log.debug("Ledger schema already initialized; skipping");
            return;
        }
        log.info("Ledger schema: loading script from classpath {}", SCHEMA_RESOURCE);
        String sql;
        try (var in = JdbcLedgerStore.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                log.error("Ledger schema resource not found: {}. Tables olo_run, olo_run_node must exist already or schema will not be created.", SCHEMA_RESOURCE);
                return;
            }
            sql = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
            log.info("Ledger schema: loaded {} characters from {}", sql.length(), SCHEMA_RESOURCE);
        } catch (Exception e) {
            log.error("Ledger schema load failed: resource={}, error={}", SCHEMA_RESOURCE, e.getMessage(), e);
            throw new RuntimeException("Ledger schema load failed: " + e.getMessage(), e);
        }
        String[] statements = sql.split(";");
        int total = 0;
        for (String raw : statements) {
            if (raw.replaceAll("(?m)^\\s*--[^\n]*\n?", "").trim().isEmpty()) continue;
            total++;
        }
        log.info("Ledger schema: connecting to DB {}:{}/{} and executing {} statement(s)", config.getDbHost(), config.getDbPort(), config.getDbName(), total);
        try (Connection c = connection(); Statement st = c.createStatement()) {
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

    private Connection connection() throws SQLException {
        String url = "jdbc:postgresql://" + config.getDbHost() + ":" + config.getDbPort() + "/" + config.getDbName();
        // Force UTC for the connection so the driver does not send JVM default (e.g. Asia/Calcutta) which some PostgreSQL setups reject.
        TimeZone prev = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            return DriverManager.getConnection(url, config.getDbUser(), config.getDbPassword() != null ? config.getDbPassword() : "");
        } finally {
            TimeZone.setDefault(prev);
        }
    }

    /** Parses a string to UUID; returns null for null or blank. Throws IllegalArgumentException for invalid UUID. */
    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID: " + s, e);
        }
    }

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis) {
        runStarted(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, inputJson, startTimeMillis, null, null);
    }

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis,
                           String pipelineChecksum, String executionEngineVersion) {
        String sql = "INSERT INTO " + TABLE_RUN + " (run_id, tenant_id, pipeline, input_json, start_time, status) VALUES (?,?,?,?::jsonb,?,?)";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, parseUuid(runId));
            ps.setObject(2, parseUuid(tenantId));
            ps.setString(3, pipeline);
            ps.setString(4, inputJson != null ? inputJson : "{}");
            ps.setTimestamp(5, new Timestamp(startTimeMillis));
            ps.setString(6, STATUS_RUNNING);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger persist failed: runStarted runId={} tenantId={} pipeline={} error={} SQLState={}", runId, tenantId, pipeline, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger runStarted failed", e);
        }
        configRecorded(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson);
    }

    @Override
    public void configRecorded(String runId, String tenantId, String pipeline, String configVersion,
                              String snapshotVersionId, String pluginVersionsJson) {
        String sql = "INSERT INTO " + TABLE_CONFIG + " (run_id, tenant_id, pipeline, config_version, snapshot_version_id, plugin_versions, created_at) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, parseUuid(runId));
            ps.setObject(2, parseUuid(tenantId));
            ps.setString(3, pipeline);
            ps.setString(4, configVersion);
            ps.setString(5, snapshotVersionId);
            ps.setString(6, pluginVersionsJson);
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger persist failed: configRecorded runId={} tenantId={} pipeline={} error={} SQLState={}", runId, tenantId, pipeline, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger configRecorded failed", e);
        }
    }

    @Override
    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status) {
        runEnded(runId, endTimeMillis, finalOutput, status, null, null, null, null, null, null);
    }

    @Override
    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status, Long durationMs) {
        runEnded(runId, endTimeMillis, finalOutput, status, durationMs, null, null, null, null, null);
    }

    @Override
    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status, Long durationMs,
                         String errorMessage, String failureStage, Integer totalPromptTokens, Integer totalCompletionTokens, String currency) {
        String sql = "UPDATE " + TABLE_RUN + " SET end_time=?, final_output=?, status=?, error_message=?, failure_stage=?, total_prompt_tokens=?, total_completion_tokens=?, currency=? WHERE run_id=?";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, new Timestamp(endTimeMillis));
            ps.setString(2, finalOutput);
            ps.setString(3, status != null ? status : STATUS_FAILED);
            ps.setString(4, errorMessage);
            ps.setString(5, failureStage);
            ps.setObject(6, totalPromptTokens);
            ps.setObject(7, totalCompletionTokens);
            ps.setString(8, currency);
            ps.setObject(9, parseUuid(runId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger persist failed: runEnded runId={} error={} SQLState={}", runId, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger runEnded failed", e);
        }
        String aggSql = "UPDATE " + TABLE_RUN + " SET duration_ms=?, total_nodes=(SELECT COUNT(*) FROM " + TABLE_NODE + " WHERE run_id=?), " +
                "total_cost=(SELECT COALESCE(SUM(COALESCE(total_cost, estimated_cost, 0)),0) FROM " + TABLE_NODE + " WHERE run_id=?), " +
                "total_tokens=(SELECT COALESCE(SUM(COALESCE(token_input_count,0)+COALESCE(token_output_count,0)),0) FROM " + TABLE_NODE + " WHERE run_id=?) WHERE run_id=?";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(aggSql)) {
            ps.setObject(1, durationMs);
            ps.setObject(2, parseUuid(runId));
            ps.setObject(3, parseUuid(runId));
            ps.setObject(4, parseUuid(runId));
            ps.setObject(5, parseUuid(runId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("Ledger run aggregates skipped (columns may be missing): {}", e.getMessage());
        }
    }

    @Override
    public void nodeStarted(String runId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
        nodeStarted(runId, null, nodeId, nodeType, inputSnapshotJson, startTimeMillis, null, null, null);
    }

    @Override
    public void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
        nodeStarted(runId, tenantId, nodeId, nodeType, inputSnapshotJson, startTimeMillis, null, null, null);
    }

    @Override
    public void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis,
                            String parentNodeId, Integer executionOrder, Integer depth) {
        String sql = "INSERT INTO " + TABLE_NODE + " (run_id, tenant_id, node_id, node_type, input_snapshot, start_time, status, parent_node_id, execution_order, depth) VALUES (?,?,?,?,?::jsonb,?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, parseUuid(runId));
            ps.setObject(2, tenantId != null && !tenantId.isBlank() ? parseUuid(tenantId) : null);
            ps.setObject(3, parseUuid(nodeId));
            ps.setString(4, nodeType);
            ps.setString(5, inputSnapshotJson != null ? inputSnapshotJson : "{}");
            ps.setTimestamp(6, new Timestamp(startTimeMillis));
            ps.setString(7, STATUS_RUNNING);
            ps.setObject(8, parentNodeId != null && !parentNodeId.isBlank() ? parseUuid(parentNodeId) : null);
            ps.setObject(9, executionOrder);
            ps.setObject(10, depth);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger persist failed: nodeStarted runId={} nodeId={} nodeType={} error={} SQLState={}", runId, nodeId, nodeType, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger nodeStarted failed", e);
        }
    }

    @Override
    public void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage) {
        nodeEnded(runId, nodeId, outputSnapshotJson, endTimeMillis, status, errorMessage, null, null, null);
    }

    @Override
    public void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage,
                          NodeAiMetrics aiMetrics, NodeReplayMeta replayMeta, NodeFailureMeta failureMeta) {
        String sql = "UPDATE " + TABLE_NODE + " SET output_snapshot=?::jsonb, end_time=?, status=?, error_code=?, error_message=?, error_details=?::jsonb, " +
                "token_input_count=?, token_output_count=?, estimated_cost=?, prompt_cost=?, completion_cost=?, total_cost=?, model_name=?, provider=?, " +
                "prompt_hash=?, model_config_json=?::jsonb, tool_calls_json=?::jsonb, external_payload_ref=?, temperature=?, top_p=?, provider_request_id=?, " +
                "retry_count=?, attempt=?, max_attempts=?, backoff_ms=?, execution_stage=?, failure_type=? WHERE run_id=? AND node_id=?";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, outputSnapshotJson != null ? outputSnapshotJson : "{}");
            ps.setTimestamp(i++, new Timestamp(endTimeMillis));
            ps.setString(i++, STATUS_SUCCESS.equals(status) ? STATUS_SUCCESS : STATUS_FAILED);
            ps.setString(i++, failureMeta != null ? failureMeta.getErrorCode() : null);
            ps.setString(i++, errorMessage);
            ps.setString(i++, failureMeta != null ? failureMeta.getErrorDetailsJson() : null);
            i = setAiMetrics(ps, i, aiMetrics);
            i = setReplayMeta(ps, i, replayMeta);
            i = setFailureMeta(ps, i, failureMeta);
            ps.setObject(i++, parseUuid(runId));
            ps.setObject(i++, parseUuid(nodeId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ledger persist failed: nodeEnded runId={} nodeId={} error={} SQLState={}", runId, nodeId, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger nodeEnded failed", e);
        }
    }

    private static int setAiMetrics(PreparedStatement ps, int startIdx, NodeAiMetrics m) throws SQLException {
        if (m == null || m.isEmpty()) {
            ps.setNull(startIdx, java.sql.Types.INTEGER);
            ps.setNull(startIdx + 1, java.sql.Types.INTEGER);
            ps.setNull(startIdx + 2, java.sql.Types.DECIMAL);
            ps.setNull(startIdx + 3, java.sql.Types.DECIMAL);
            ps.setNull(startIdx + 4, java.sql.Types.DECIMAL);
            ps.setNull(startIdx + 5, java.sql.Types.DECIMAL);
            ps.setString(startIdx + 6, null);
            ps.setString(startIdx + 7, null);
            return startIdx + 8;
        }
        ps.setObject(startIdx, m.getTokenInputCount());
        ps.setObject(startIdx + 1, m.getTokenOutputCount());
        ps.setObject(startIdx + 2, m.getEstimatedCost());
        ps.setObject(startIdx + 3, m.getPromptCost());
        ps.setObject(startIdx + 4, m.getCompletionCost());
        ps.setObject(startIdx + 5, m.getTotalCost());
        ps.setString(startIdx + 6, m.getModelName());
        ps.setString(startIdx + 7, m.getProvider());
        return startIdx + 8;
    }

    private static int setReplayMeta(PreparedStatement ps, int startIdx, NodeReplayMeta m) throws SQLException {
        if (m == null || m.isEmpty()) {
            ps.setString(startIdx, null);
            ps.setString(startIdx + 1, null);
            ps.setString(startIdx + 2, null);
            ps.setString(startIdx + 3, null);
            ps.setNull(startIdx + 4, java.sql.Types.DECIMAL);
            ps.setNull(startIdx + 5, java.sql.Types.DECIMAL);
            ps.setString(startIdx + 6, null);
            return startIdx + 7;
        }
        ps.setString(startIdx, m.getPromptHash());
        ps.setString(startIdx + 1, m.getModelConfigJson());
        ps.setString(startIdx + 2, m.getToolCallsJson());
        ps.setString(startIdx + 3, m.getExternalPayloadRef());
        ps.setObject(startIdx + 4, m.getTemperature());
        ps.setObject(startIdx + 5, m.getTopP());
        ps.setString(startIdx + 6, m.getProviderRequestId());
        return startIdx + 7;
    }

    private static int setFailureMeta(PreparedStatement ps, int startIdx, NodeFailureMeta m) throws SQLException {
        if (m == null || m.isEmpty()) {
            ps.setNull(startIdx, java.sql.Types.INTEGER);
            ps.setObject(startIdx + 1, null);
            ps.setNull(startIdx + 2, java.sql.Types.INTEGER);
            ps.setNull(startIdx + 3, java.sql.Types.BIGINT);
            ps.setString(startIdx + 4, null);
            ps.setString(startIdx + 5, null);
            return startIdx + 6;
        }
        ps.setObject(startIdx, m.getRetryCount());
        ps.setObject(startIdx + 1, m.getAttemptNumber());
        ps.setObject(startIdx + 2, m.getMaxAttempts());
        ps.setObject(startIdx + 3, m.getBackoffMs());
        ps.setString(startIdx + 4, m.getExecutionStage());
        ps.setString(startIdx + 5, m.getFailureType());
        return startIdx + 6;
    }
}
