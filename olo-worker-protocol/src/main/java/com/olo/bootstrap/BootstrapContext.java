package com.olo.bootstrap;

import com.olo.config.OloConfig;
import com.olo.executiontree.config.PipelineConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Read-write view of bootstrap context: config, task queues, tenants, pipeline configs,
 * and contributor data. Implementations live in {@code olo-worker-bootstrap}; this
 * interface is in {@code olo-worker-protocol} so plugins, tools, and features can depend
 * on the contract only.
 */
public interface BootstrapContext {

    /** Configuration built from environment (OLO_QUEUE, OLO_TENANT_IDS, etc.). */
    OloConfig getConfig();

    /** Task queue names from config. */
    List<String> getTaskQueues();

    /** Tenant ids loaded at bootstrap (from Redis or OLO_TENANT_IDS). */
    List<String> getTenantIds();

    /** Read-only map: composite key "tenant:queue" → pipeline configuration. */
    Map<String, PipelineConfiguration> getPipelineConfigByQueue();

    /**
     * Temporal connection target from pipeline configuration, or default.
     * Uses first available queue's executionDefaults.temporal.target.
     */
    String getTemporalTargetOrDefault(String defaultTarget);

    /**
     * Temporal namespace from pipeline configuration, or default.
     * Uses first available queue's executionDefaults.temporal.namespace.
     */
    String getTemporalNamespaceOrDefault(String defaultNamespace);

    /** Stores data from a {@link BootstrapContributor}. */
    void putContributorData(String key, Object value);

    /** Returns contributor data for the given key, or null. */
    Object getContributorData(String key);

    /** Read-only view of all contributor data. */
    Map<String, Object> getContributorData();

    /** Returns the pipeline configuration for the given composite key, or null. */
    default PipelineConfiguration getPipelineConfig(String key) {
        return getPipelineConfigByQueue() != null ? getPipelineConfigByQueue().get(key) : null;
    }
}
