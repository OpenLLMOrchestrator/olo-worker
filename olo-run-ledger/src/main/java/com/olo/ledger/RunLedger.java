package com.olo.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-safe facade for the run ledger. All writes delegate to {@link LedgerStore};
 * any exception from the store is caught, logged, and not rethrown so execution never fails.
 */
public final class RunLedger {

    private static final Logger log = LoggerFactory.getLogger(RunLedger.class);

    private final LedgerStore store;

    public RunLedger(LedgerStore store) {
        this.store = store != null ? store : new NoOpLedgerStore();
    }

    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis) {
        try {
            store.runStarted(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, inputJson, startTimeMillis);
        } catch (Throwable t) {
            log.warn("Ledger runStarted failed (runId={}); execution continues. Fix DB schema or connection. Error: {}", runId, t.getMessage(), t);
        }
    }

    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis,
                           String pipelineChecksum, String executionEngineVersion) {
        try {
            store.runStarted(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, inputJson, startTimeMillis, pipelineChecksum, executionEngineVersion);
        } catch (Throwable t) {
            log.warn("Ledger runStarted failed (runId={}); execution continues. Fix DB schema or connection. Error: {}", runId, t.getMessage(), t);
        }
    }

    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status) {
        try {
            store.runEnded(runId, endTimeMillis, finalOutput, status);
        } catch (Throwable t) {
            log.warn("Ledger runEnded failed (runId={}); execution continues. Error: {}", runId, t.getMessage(), t);
        }
    }

    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status, Long durationMs) {
        try {
            store.runEnded(runId, endTimeMillis, finalOutput, status, durationMs, null, null, null, null, null);
        } catch (Throwable t) {
            log.warn("Ledger runEnded failed (runId={}); execution continues. Error: {}", runId, t.getMessage(), t);
        }
    }

    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status, Long durationMs,
                         String errorMessage, String failureStage, Integer totalPromptTokens, Integer totalCompletionTokens, String currency) {
        try {
            store.runEnded(runId, endTimeMillis, finalOutput, status, durationMs, errorMessage, failureStage, totalPromptTokens, totalCompletionTokens, currency);
        } catch (Throwable t) {
            log.warn("Ledger runEnded failed (runId={}); execution continues. Error: {}", runId, t.getMessage(), t);
        }
    }

    public void nodeStarted(String runId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
        try {
            store.nodeStarted(runId, null, nodeId, nodeType, inputSnapshotJson, startTimeMillis);
        } catch (Throwable t) {
            log.warn("Ledger nodeStarted failed (runId={}, nodeId={}); execution continues. Error: {}", runId, nodeId, t.getMessage(), t);
        }
    }

    public void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
        try {
            store.nodeStarted(runId, tenantId, nodeId, nodeType, inputSnapshotJson, startTimeMillis);
        } catch (Throwable t) {
            log.warn("Ledger nodeStarted failed (runId={}, nodeId={}); execution continues. Error: {}", runId, nodeId, t.getMessage(), t);
        }
    }

    public void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis,
                            String parentNodeId, Integer executionOrder, Integer depth) {
        try {
            store.nodeStarted(runId, tenantId, nodeId, nodeType, inputSnapshotJson, startTimeMillis, parentNodeId, executionOrder, depth);
        } catch (Throwable t) {
            log.warn("Ledger nodeStarted failed (runId={}, nodeId={}); execution continues. Error: {}", runId, nodeId, t.getMessage(), t);
        }
    }

    public void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage) {
        try {
            store.nodeEnded(runId, nodeId, outputSnapshotJson, endTimeMillis, status, errorMessage);
        } catch (Throwable t) {
            log.warn("Ledger nodeEnded failed (runId={}, nodeId={}); execution continues. Error: {}", runId, nodeId, t.getMessage(), t);
        }
    }

    public void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage,
                          NodeAiMetrics aiMetrics, NodeReplayMeta replayMeta, NodeFailureMeta failureMeta) {
        try {
            store.nodeEnded(runId, nodeId, outputSnapshotJson, endTimeMillis, status, errorMessage, aiMetrics, replayMeta, failureMeta);
        } catch (Throwable t) {
            log.warn("Ledger nodeEnded failed (runId={}, nodeId={}); execution continues. Error: {}", runId, nodeId, t.getMessage(), t);
        }
    }

    public void configRecorded(String runId, String tenantId, String pipeline, String configVersion,
                              String snapshotVersionId, String pluginVersionsJson) {
        try {
            store.configRecorded(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson);
        } catch (Throwable t) {
            log.warn("Ledger configRecorded failed (runId={}); execution continues. Error: {}", runId, t.getMessage(), t);
        }
    }
}
