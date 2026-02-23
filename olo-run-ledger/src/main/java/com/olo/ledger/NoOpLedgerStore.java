package com.olo.ledger;

/** No-op LedgerStore when ledger is disabled or for tests. */
public final class NoOpLedgerStore implements LedgerStore {

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis) {
    }

    @Override
    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status) {
    }

    @Override
    public void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
    }

    @Override
    public void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage) {
    }
}
