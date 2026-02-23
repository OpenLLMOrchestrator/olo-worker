package com.olo.executioncontext;

import com.olo.executiontree.config.PipelineConfiguration;

import java.util.Objects;

/**
 * Immutable snapshot of pipeline configuration for a single execution run.
 * Created at activity start from {@link LocalContext}; no global config reads during the run.
 * Carries an optional snapshot version id for execution version pinning and auditing.
 */
public final class ExecutionConfigSnapshot {

    private final String tenantId;
    private final String queueName;
    private final PipelineConfiguration pipelineConfiguration;
    private final String snapshotVersionId;
    private final String runId;

    private ExecutionConfigSnapshot(String tenantId, String queueName,
                                    PipelineConfiguration pipelineConfiguration,
                                    String snapshotVersionId,
                                    String runId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.queueName = queueName != null ? queueName : "";
        this.pipelineConfiguration = Objects.requireNonNull(pipelineConfiguration, "pipelineConfiguration");
        this.snapshotVersionId = snapshotVersionId != null ? snapshotVersionId : "";
        this.runId = runId;
    }

    /**
     * Creates an immutable snapshot for the execution engine.
     *
     * @param tenantId             tenant id
     * @param queueName            queue name (e.g. task queue)
     * @param pipelineConfiguration deep-copied config (must not be mutated during the run)
     * @param snapshotVersionId    optional version id (e.g. config version from input or config.getVersion())
     */
    public static ExecutionConfigSnapshot of(String tenantId, String queueName,
                                             PipelineConfiguration pipelineConfiguration,
                                             String snapshotVersionId) {
        return new ExecutionConfigSnapshot(tenantId, queueName, pipelineConfiguration, snapshotVersionId, null);
    }

    /**
     * Creates an immutable snapshot with an optional run id for ledger context (used when execution may run in a different thread, e.g. ASYNC).
     */
    public static ExecutionConfigSnapshot of(String tenantId, String queueName,
                                             PipelineConfiguration pipelineConfiguration,
                                             String snapshotVersionId,
                                             String runId) {
        return new ExecutionConfigSnapshot(tenantId, queueName, pipelineConfiguration, snapshotVersionId, runId);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getQueueName() {
        return queueName;
    }

    /** Immutable pipeline configuration for this run. Do not mutate. */
    public PipelineConfiguration getPipelineConfiguration() {
        return pipelineConfiguration;
    }

    /** Version id used for this snapshot (for pinning and auditing). */
    public String getSnapshotVersionId() {
        return snapshotVersionId;
    }

    /** Optional run id for ledger; set so the thread that runs nodes can set LedgerContext (e.g. when ASYNC). */
    public String getRunId() {
        return runId;
    }
}
