package com.olo.bootstrap;

import com.olo.config.OloConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.defaults.ExecutionDefaults;
import com.olo.executiontree.defaults.TemporalDefaults;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory global context created at bootstrap: env-derived configuration plus
 * a read-only map of queue name to deserialized pipeline configuration.
 */
public final class GlobalContext {

    private final OloConfig config;
    private final List<String> taskQueues;
    private final Map<String, PipelineConfiguration> pipelineConfigByQueue;

    public GlobalContext(
            OloConfig config,
            Map<String, PipelineConfiguration> pipelineConfigByQueue) {
        this.config = Objects.requireNonNull(config, "config");
        this.taskQueues = config.getTaskQueues();
        this.pipelineConfigByQueue = pipelineConfigByQueue == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(pipelineConfigByQueue);
    }

    /** Configuration built from environment (OLO_QUEUE, OLO_CACHE_*, etc.). */
    public OloConfig getConfig() {
        return config;
    }

    /** Task queue names from config (same as {@code getConfig().getTaskQueues()}). */
    public List<String> getTaskQueues() {
        return taskQueues;
    }

    /** Read-only map: queue name → deserialized pipeline configuration. */
    public Map<String, PipelineConfiguration> getPipelineConfigByQueue() {
        return pipelineConfigByQueue;
    }

    /** Returns the pipeline configuration for the given queue, or null if not loaded. */
    public PipelineConfiguration getPipelineConfig(String queueName) {
        return pipelineConfigByQueue.get(queueName);
    }

    /**
     * Temporal connection target (e.g. {@code localhost:7233}) from pipeline configuration.
     * Uses the first available queue's {@code executionDefaults.temporal.target}; falls back to default if none set.
     */
    public String getTemporalTargetOrDefault(String defaultTarget) {
        for (PipelineConfiguration pc : pipelineConfigByQueue.values()) {
            ExecutionDefaults ed = pc != null ? pc.getExecutionDefaults() : null;
            TemporalDefaults td = ed != null ? ed.getTemporal() : null;
            if (td != null && td.getTarget() != null && !td.getTarget().isBlank()) {
                return td.getTarget().trim();
            }
        }
        return defaultTarget != null ? defaultTarget : "localhost:7233";
    }

    /**
     * Temporal namespace from pipeline configuration.
     * Uses the first available queue's {@code executionDefaults.temporal.namespace}; falls back to default if none set.
     */
    public String getTemporalNamespaceOrDefault(String defaultNamespace) {
        for (PipelineConfiguration pc : pipelineConfigByQueue.values()) {
            ExecutionDefaults ed = pc != null ? pc.getExecutionDefaults() : null;
            TemporalDefaults td = ed != null ? ed.getTemporal() : null;
            if (td != null && td.getNamespace() != null && !td.getNamespace().isBlank()) {
                return td.getNamespace().trim();
            }
        }
        return defaultNamespace != null ? defaultNamespace : "default";
    }
}
