package com.olo.ledger;

/**
 * Write-only store for run and node ledger records.
 * Implementations persist to DB (e.g. tables olo_run, olo_run_node).
 * RunLedger wraps all calls in try/catch so execution never fails.
 * Extended methods support AI cost (MODEL/PLANNER), replay metadata, failure meta, and run aggregations.
 */
public interface LedgerStore {

    void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                    String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis);

    /** Run start with optional pipeline checksum and execution engine version (compliance/audit). */
    default void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                            String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis,
                            String pipelineChecksum, String executionEngineVersion) {
        runStarted(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, inputJson, startTimeMillis);
    }

    void runEnded(String runId, long endTimeMillis, String finalOutput, String status);

    /** Run end with duration and optional aggregation (total_nodes, total_cost, total_tokens computed in store if null). */
    default void runEnded(String runId, long endTimeMillis, String finalOutput, String status, Long durationMs) {
        runEnded(runId, endTimeMillis, finalOutput, status, durationMs, null, null, null, null, null);
    }

    /** Run end with run-level error/token/cost fields (error_message, failure_stage, total_prompt_tokens, total_completion_tokens, currency). */
    default void runEnded(String runId, long endTimeMillis, String finalOutput, String status, Long durationMs,
                          String errorMessage, String failureStage, Integer totalPromptTokens, Integer totalCompletionTokens, String currency) {
        runEnded(runId, endTimeMillis, finalOutput, status, durationMs);
    }

    /** Node start (tenant_id null: backward compat). Prefer {@link #nodeStarted(String, String, String, String, String, long)} for analytics. */
    default void nodeStarted(String runId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
        nodeStarted(runId, null, nodeId, nodeType, inputSnapshotJson, startTimeMillis);
    }

    void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis);

    /** Node start with tenant_id + execution hierarchy (parent_node_id, execution_order, depth) for tree reconstruction. */
    default void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis,
                             String parentNodeId, Integer executionOrder, Integer depth) {
        nodeStarted(runId, tenantId, nodeId, nodeType, inputSnapshotJson, startTimeMillis);
    }

    void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage);

    /**
     * Record which config was used for a run (writes to olo_config for audit).
     * Call after runStarted so olo_run row exists (FK).
     */
    default void configRecorded(String runId, String tenantId, String pipeline, String configVersion,
                                String snapshotVersionId, String pluginVersionsJson) {
        // no-op by default
    }

    /** Node end with AI metrics (MODEL/PLANNER), replay meta, and failure meta. */
    default void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage,
                           NodeAiMetrics aiMetrics, NodeReplayMeta replayMeta, NodeFailureMeta failureMeta) {
        nodeEnded(runId, nodeId, outputSnapshotJson, endTimeMillis, status, errorMessage);
    }
}
