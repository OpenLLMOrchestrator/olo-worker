package com.olo.ledger.store;

import com.olo.ledger.NodeAiMetrics;
import com.olo.ledger.NodeFailureMeta;
import com.olo.ledger.NodeReplayMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Single responsibility: write node start/end records to olo_run_node.
 */
public final class NodeWriter {

    private static final String TABLE_NODE = "olo_run_node";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final Logger log = LoggerFactory.getLogger(NodeWriter.class);

    public void nodeStarted(Connection c, String runId, String tenantId, String nodeId, String nodeType,
                            String inputSnapshotJson, long startTimeMillis,
                            String parentNodeId, Integer executionOrder, Integer depth) throws SQLException {
        String sql = "INSERT INTO " + TABLE_NODE + " (run_id, tenant_id, tenant_name, node_id, node_name, node_type, input_snapshot, start_time, status, parent_node_id, parent_node_name, execution_order, depth, attempt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, LedgerSqlUtils.toUuid(runId));
            ps.setObject(2, tenantId != null && !tenantId.isBlank() ? LedgerSqlUtils.toUuid(tenantId) : null);
            ps.setString(3, LedgerSqlUtils.toName(tenantId, LedgerSqlUtils.NAME_MAX_LEN));
            ps.setObject(4, LedgerSqlUtils.toUuid(nodeId));
            ps.setString(5, LedgerSqlUtils.toName(nodeId, LedgerSqlUtils.NAME_MAX_LEN));
            ps.setString(6, nodeType);
            ps.setObject(7, LedgerSqlUtils.toJsonbPgObject(inputSnapshotJson != null ? inputSnapshotJson : "{}"));
            ps.setTimestamp(8, new Timestamp(startTimeMillis));
            ps.setString(9, STATUS_RUNNING);
            ps.setObject(10, parentNodeId != null && !parentNodeId.isBlank() ? LedgerSqlUtils.toUuid(parentNodeId) : null);
            ps.setString(11, LedgerSqlUtils.toName(parentNodeId, LedgerSqlUtils.NAME_MAX_LEN));
            ps.setObject(12, executionOrder);
            ps.setObject(13, depth);
            ps.setObject(14, 1);
            ps.executeUpdate();
            log.info("Ledger entry created | olo_run_node | runId={} nodeId={} nodeName={} nodeType={}", runId, nodeId, LedgerSqlUtils.toName(nodeId, LedgerSqlUtils.NAME_MAX_LEN), nodeType);
        }
    }

    public void nodeEnded(Connection c, String runId, String nodeId, String outputSnapshotJson, long endTimeMillis,
                          String status, String errorMessage,
                          NodeAiMetrics aiMetrics, NodeReplayMeta replayMeta, NodeFailureMeta failureMeta) throws SQLException {
        String sql = "UPDATE " + TABLE_NODE + " SET output_snapshot=?::jsonb, end_time=?, status=?, error_code=?, error_message=?, error_details=?::jsonb, " +
                "token_input_count=?, token_output_count=?, estimated_cost=?, prompt_cost=?, completion_cost=?, total_cost=?, model_name=?, provider=?, " +
                "prompt_hash=?, model_config_json=?::jsonb, tool_calls_json=?::jsonb, external_payload_ref=?, temperature=?, top_p=?, provider_request_id=?, " +
                "retry_count=?, attempt=?, max_attempts=?, backoff_ms=?, execution_stage=?, failure_type=? WHERE run_id=? AND node_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, outputSnapshotJson != null ? outputSnapshotJson : "{}");
            ps.setTimestamp(i++, new Timestamp(endTimeMillis));
            ps.setString(i++, STATUS_SUCCESS.equals(status) ? STATUS_SUCCESS : STATUS_FAILED);
            ps.setString(i++, failureMeta != null ? failureMeta.getErrorCode() : null);
            ps.setString(i++, errorMessage);
            ps.setString(i++, failureMeta != null ? failureMeta.getErrorDetailsJson() : null);
            i = NodeMetaBinder.setAiMetrics(ps, i, aiMetrics);
            i = NodeMetaBinder.setReplayMeta(ps, i, replayMeta);
            i = NodeMetaBinder.setFailureMeta(ps, i, failureMeta);
            ps.setObject(i++, LedgerSqlUtils.toUuid(runId));
            ps.setObject(i++, LedgerSqlUtils.toUuid(nodeId));
            ps.executeUpdate();
            log.info("Ledger entry updated | olo_run_node | runId={} nodeId={} status={}", runId, nodeId, STATUS_SUCCESS.equals(status) ? STATUS_SUCCESS : STATUS_FAILED);
        }
    }
}
