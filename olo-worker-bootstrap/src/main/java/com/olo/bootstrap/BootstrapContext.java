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
 * Wrapper object returned from bootstrap. Holds env-derived configuration plus
 * a read-only map of pipeline configs (keyed by "tenant:queue" for validation).
 * <p>
 * For the runtime configuration store, see {@link com.olo.executiontree.load.GlobalConfigurationContext}.
 */
public final class BootstrapContext {

    private final OloConfig config;
    private final List<String> taskQueues;
    private final List<String> tenantIds;
    private final Map<String, PipelineConfiguration> pipelineConfigByQueue;

    public BootstrapContext(
            OloConfig config,
            Map<String, PipelineConfiguration> pipelineConfigByQueue,
            List<String> tenantIds) {
        this.config = Objects.requireNonNull(config, "config");
        this.taskQueues = config.getTaskQueues();
        this.tenantIds = tenantIds != null ? List.copyOf(tenantIds) : List.of();
        this.pipelineConfigByQueue = pipelineConfigByQueue == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(pipelineConfigByQueue);
    }

    /** @deprecated Use {@link #BootstrapContext(OloConfig, Map, List)} with tenant ids. */
    @Deprecated
    public BootstrapContext(OloConfig config, Map<String, PipelineConfiguration> pipelineConfigByQueue) {
        this(config, pipelineConfigByQueue, null);
    }

    /** Configuration built from environment (OLO_QUEUE, OLO_TENANT_IDS, OLO_CACHE_*, etc.). */
    public OloConfig getConfig() {
        return config;
    }

    /** Task queue names from config (same as {@code getConfig().getTaskQueues()}). */
    public List<String> getTaskQueues() {
        return taskQueues;
    }

    /** Tenant ids that were loaded at bootstrap (from Redis olo:tenants or OLO_TENANT_IDS). Use when registering plugins per tenant. */
    public List<String> getTenantIds() {
        return tenantIds;
    }

    /** Read-only map: composite key "tenant:queue" → deserialized pipeline configuration (for validation). */
    public Map<String, PipelineConfiguration> getPipelineConfigByQueue() {
        return pipelineConfigByQueue;
    }

    /** Returns the pipeline configuration for the given composite key, or null if not loaded. */
    public PipelineConfiguration getPipelineConfig(String key) {
        return pipelineConfigByQueue.get(key);
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
