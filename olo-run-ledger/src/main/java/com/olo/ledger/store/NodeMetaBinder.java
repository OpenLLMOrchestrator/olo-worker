package com.olo.ledger.store;

import com.olo.ledger.NodeAiMetrics;
import com.olo.ledger.NodeFailureMeta;
import com.olo.ledger.NodeReplayMeta;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Single responsibility: bind AI metrics, replay meta, and failure meta to PreparedStatement parameters.
 */
public final class NodeMetaBinder {

    private NodeMetaBinder() {}

    public static int setAiMetrics(PreparedStatement ps, int startIdx, NodeAiMetrics m) throws SQLException {
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

    public static int setReplayMeta(PreparedStatement ps, int startIdx, NodeReplayMeta m) throws SQLException {
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

    public static int setFailureMeta(PreparedStatement ps, int startIdx, NodeFailureMeta m) throws SQLException {
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
