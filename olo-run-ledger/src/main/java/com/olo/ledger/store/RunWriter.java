package com.olo.ledger.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Single responsibility: write run start/end records to olo_run (and run aggregates).
 */
public final class RunWriter {

    private static final String TABLE_RUN = "olo_run";
    private static final String TABLE_NODE = "olo_run_node";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FAILED = "FAILED";
    /** Placeholder tenant_id when ensuring run row for FK; schema requires NOT NULL. */
    private static final String UNKNOWN_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final Logger log = LoggerFactory.getLogger(RunWriter.class);

    /**
     * Ensures a row exists in olo_run for the given run_id so node inserts (FK) succeed.
     * Used when a node activity runs before the activity that called runStarted (e.g. parallel or out-of-order execution).
     * No-op if the row already exists (INSERT ... ON CONFLICT DO NOTHING).
     */
    public void ensureRunExists(Connection c, String runId, String tenantId, long startTimeMillis) throws SQLException {
        String effectiveTenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : UNKNOWN_TENANT_ID;
        String sql = "INSERT INTO " + TABLE_RUN + " (run_id, tenant_id, tenant_name, pipeline, input_json, start_time, status) VALUES (?,?,?,?,?::jsonb,?,?) ON CONFLICT (run_id) DO NOTHING";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, LedgerSqlUtils.toUuid(runId));
            ps.setObject(2, LedgerSqlUtils.toUuid(effectiveTenantId));
            ps.setString(3, LedgerSqlUtils.toName(effectiveTenantId, LedgerSqlUtils.NAME_MAX_LEN));
            ps.setString(4, "");
            ps.setString(5, "{}");
            ps.setTimestamp(6, new Timestamp(startTimeMillis));
            ps.setString(7, STATUS_RUNNING);
            ps.executeUpdate();
        }
    }

    public void runStarted(Connection c, String runId, String tenantId, String pipeline,
                           String inputJson, long startTimeMillis) throws SQLException {
        String sql = "INSERT INTO " + TABLE_RUN + " (run_id, tenant_id, tenant_name, pipeline, input_json, start_time, status) VALUES (?,?,?,?,?::jsonb,?,?) "
                + "ON CONFLICT (run_id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id, tenant_name=EXCLUDED.tenant_name, pipeline=EXCLUDED.pipeline, input_json=EXCLUDED.input_json, start_time=EXCLUDED.start_time, status=EXCLUDED.status";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, LedgerSqlUtils.toUuid(runId));
            ps.setObject(2, LedgerSqlUtils.toUuid(tenantId));
            ps.setString(3, LedgerSqlUtils.toName(tenantId, LedgerSqlUtils.NAME_MAX_LEN));
            ps.setString(4, pipeline);
            ps.setString(5, inputJson != null ? inputJson : "{}");
            ps.setTimestamp(6, new Timestamp(startTimeMillis));
            ps.setString(7, STATUS_RUNNING);
            ps.executeUpdate();
            log.info("Ledger entry created | olo_run | runId={} tenantId={} tenantName={} pipeline={}", runId, tenantId, LedgerSqlUtils.toName(tenantId, LedgerSqlUtils.NAME_MAX_LEN), pipeline);
        }
    }

    public void runEnded(Connection c, String runId, long endTimeMillis, String finalOutput, String status,
                         Long durationMs, String errorMessage, String failureStage,
                         Integer totalPromptTokens, Integer totalCompletionTokens, String currency) throws SQLException {
        String sql = "UPDATE " + TABLE_RUN + " SET end_time=?, final_output=?, status=?, error_message=?, failure_stage=?, total_prompt_tokens=?, total_completion_tokens=?, currency=? WHERE run_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, new Timestamp(endTimeMillis));
            ps.setString(2, finalOutput);
            ps.setString(3, status != null ? status : STATUS_FAILED);
            ps.setString(4, errorMessage);
            ps.setString(5, failureStage);
            ps.setObject(6, totalPromptTokens);
            ps.setObject(7, totalCompletionTokens);
            ps.setString(8, currency);
            ps.setObject(9, LedgerSqlUtils.toUuid(runId));
            ps.executeUpdate();
            log.info("Ledger entry updated | olo_run | runId={} status={}", runId, status != null ? status : STATUS_FAILED);
        }
        runAggregates(c, runId, durationMs);
    }

    private void runAggregates(Connection c, String runId, Long durationMs) {
        String aggSql = "UPDATE " + TABLE_RUN + " SET duration_ms=?, total_nodes=(SELECT COUNT(*) FROM " + TABLE_NODE + " WHERE run_id=?), " +
                "total_cost=(SELECT COALESCE(SUM(COALESCE(total_cost, estimated_cost, 0)),0) FROM " + TABLE_NODE + " WHERE run_id=?), " +
                "total_tokens=(SELECT COALESCE(SUM(COALESCE(token_input_count,0)+COALESCE(token_output_count,0)),0) FROM " + TABLE_NODE + " WHERE run_id=?) WHERE run_id=?";
        try (PreparedStatement ps = c.prepareStatement(aggSql)) {
            ps.setObject(1, durationMs);
            ps.setObject(2, LedgerSqlUtils.toUuid(runId));
            ps.setObject(3, LedgerSqlUtils.toUuid(runId));
            ps.setObject(4, LedgerSqlUtils.toUuid(runId));
            ps.setObject(5, LedgerSqlUtils.toUuid(runId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("Ledger run aggregates skipped (columns may be missing): {}", e.getMessage());
        }
    }
}
