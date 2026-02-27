package com.olo.ledger;

import com.olo.config.OloConfig;
import com.olo.ledger.schema.LedgerSchemaBootstrapper;
import com.olo.ledger.store.ConfigWriter;
import com.olo.ledger.store.JdbcLedgerConnectionProvider;
import com.olo.ledger.store.NodeWriter;
import com.olo.ledger.store.RunWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC implementation of LedgerStore. Delegates to dedicated writers and schema bootstrapper.
 * Single responsibility: implement LedgerStore by coordinating connection, schema, and writers.
 */
public final class JdbcLedgerStore implements LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcLedgerStore.class);

    private final JdbcLedgerConnectionProvider connectionProvider;
    private final LedgerSchemaBootstrapper schemaBootstrapper;
    private final RunWriter runWriter;
    private final NodeWriter nodeWriter;
    private final ConfigWriter configWriter;

    public JdbcLedgerStore(OloConfig config) {
        this.connectionProvider = new JdbcLedgerConnectionProvider(config);
        this.schemaBootstrapper = new LedgerSchemaBootstrapper(config);
        this.runWriter = new RunWriter();
        this.nodeWriter = new NodeWriter();
        this.configWriter = new ConfigWriter();
    }

    /**
     * Creates ledger tables and indexes if they do not exist. Idempotent; safe to call at bootstrap.
     */
    public void ensureSchema() {
        schemaBootstrapper.ensureSchema(connectionProvider::getConnection);
    }

    private Connection connection() throws SQLException {
        return connectionProvider.getConnection();
    }

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis) {
        runStarted(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, inputJson, startTimeMillis, null, null, null, null);
    }

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis,
                           String pipelineChecksum, String executionEngineVersion) {
        runStarted(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, inputJson, startTimeMillis, pipelineChecksum, executionEngineVersion, null, null);
    }

    @Override
    public void runStarted(String runId, String tenantId, String pipeline, String configVersion,
                           String snapshotVersionId, String pluginVersionsJson, String inputJson, long startTimeMillis,
                           String pipelineChecksum, String executionEngineVersion,
                           String configTreeJson, String tenantConfigJson) {
        try (Connection c = connection()) {
            runWriter.runStarted(c, runId, tenantId, pipeline, inputJson, startTimeMillis);
        } catch (SQLException e) {
            log.error("Ledger persist failed: runStarted runId={} tenantId={} pipeline={} error={} SQLState={}", runId, tenantId, pipeline, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger runStarted failed", e);
        }
        configRecorded(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, configTreeJson, tenantConfigJson);
    }

    @Override
    public void configRecorded(String runId, String tenantId, String pipeline, String configVersion,
                              String snapshotVersionId, String pluginVersionsJson) {
        configRecorded(runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, null, null);
    }

    @Override
    public void configRecorded(String runId, String tenantId, String pipeline, String configVersion,
                              String snapshotVersionId, String pluginVersionsJson,
                              String configTreeJson, String tenantConfigJson) {
        try (Connection c = connection()) {
            configWriter.configRecorded(c, runId, tenantId, pipeline, configVersion, snapshotVersionId, pluginVersionsJson, configTreeJson, tenantConfigJson);
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
        try (Connection c = connection()) {
            runWriter.runEnded(c, runId, endTimeMillis, finalOutput, status, durationMs, errorMessage, failureStage, totalPromptTokens, totalCompletionTokens, currency);
        } catch (SQLException e) {
            log.error("Ledger persist failed: runEnded runId={} error={} SQLState={}", runId, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger runEnded failed", e);
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
        try (Connection c = connection()) {
            nodeWriter.nodeStarted(c, runId, tenantId, nodeId, nodeType, inputSnapshotJson, startTimeMillis, parentNodeId, executionOrder, depth);
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
        try (Connection c = connection()) {
            nodeWriter.nodeEnded(c, runId, nodeId, outputSnapshotJson, endTimeMillis, status, errorMessage, aiMetrics, replayMeta, failureMeta);
        } catch (SQLException e) {
            log.error("Ledger persist failed: nodeEnded runId={} nodeId={} error={} SQLState={}", runId, nodeId, e.getMessage(), e.getSQLState(), e);
            throw new RuntimeException("Ledger nodeEnded failed", e);
        }
    }
}
