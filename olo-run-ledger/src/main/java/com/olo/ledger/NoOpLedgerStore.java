package com.olo.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** No-op LedgerStore when ledger is disabled or JDBC init failed. Logs so users see that ledger path was hit but no DB writes occur. */
public final class NoOpLedgerStore implements LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(NoOpLedgerStore.class);

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis) {
        log.info("Ledger (no-op): runStarted | runId={} | pipeline={} | persistence skipped (JDBC unavailable or disabled)", runId, pipeline);
    }

    @Override
    public void runEnded(String runId, long endTimeMillis, String finalOutput, String status) {
        log.info("Ledger (no-op): runEnded | runId={} | status={}", runId, status);
    }

    @Override
    public void nodeStarted(String runId, String tenantId, String nodeId, String nodeType, String inputSnapshotJson, long startTimeMillis) {
        log.info("Ledger (no-op): nodeStarted | runId={} | nodeId={} | nodeType={}", runId, nodeId, nodeType);
    }

    @Override
    public void nodeEnded(String runId, String nodeId, String outputSnapshotJson, long endTimeMillis, String status, String errorMessage) {
        log.info("Ledger (no-op): nodeEnded | runId={} | nodeId={} | status={}", runId, nodeId, status);
    }
}
