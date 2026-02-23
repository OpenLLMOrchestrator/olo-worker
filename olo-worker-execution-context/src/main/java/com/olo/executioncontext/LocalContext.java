package com.olo.executioncontext;

import com.olo.executiontree.ExecutionTreeConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.load.GlobalConfigurationContext;
import com.olo.executiontree.load.GlobalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Local (per-workflow) execution context holding a deep copy of the pipeline configuration
 * (execution tree) for the queue where the workflow was created.
 * <p>
 * Create via {@link #forQueue(String)} when a new workflow starts: obtains the configuration
 * for that queue from the global context and stores a deep copy so the workflow can use it
 * without being affected by later global changes.
 */
public final class LocalContext {

    private static final Logger log = LoggerFactory.getLogger(LocalContext.class);

    private final String queueName;
    private final PipelineConfiguration pipelineConfiguration;

    private LocalContext(String queueName, PipelineConfiguration pipelineConfiguration) {
        this.queueName = Objects.requireNonNull(queueName, "queueName");
        this.pipelineConfiguration = Objects.requireNonNull(pipelineConfiguration, "pipelineConfiguration");
    }

    /**
     * Creates a local context for the given tenant and queue by taking a deep copy of the pipeline
     * configuration from the global context. Call this when a new workflow starts.
     *
     * @param tenantKey tenant id (use {@link com.olo.config.OloConfig#normalizeTenantId(String)} if from workflow context)
     * @param queueName task queue name (e.g. olo-chat-queue-oolama or olo-chat-queue-oolama-debug)
     * @return local context with a deep copy of the execution tree for that queue, or null if no config is loaded for the tenant/queue
     */
    public static LocalContext forQueue(String tenantKey, String queueName) {
        return forQueue(tenantKey, queueName, null);
    }

    /**
     * Creates a local context for the given tenant, queue and optional config version (execution version pinning).
     * When {@code configVersion} is non-null and non-blank, the loaded config's version must match; otherwise returns null.
     *
     * @param tenantKey    tenant id
     * @param queueName    task queue name
     * @param configVersion optional version to pin to (e.g. from routing); null = no version check
     * @return local context with a deep copy, or null if no config or version mismatch
     */
    public static LocalContext forQueue(String tenantKey, String queueName, String configVersion) {
        Objects.requireNonNull(queueName, "queueName");
        String tenant = tenantKey != null && !tenantKey.isBlank() ? tenantKey.trim() : "default";
        GlobalContext global = GlobalConfigurationContext.get(tenant, queueName);
        if (global == null) {
            log.warn("No global pipeline configuration for tenant={} queue={}; cannot create LocalContext", tenant, queueName);
            return null;
        }
        PipelineConfiguration source = global.getConfiguration();
        if (configVersion != null && !configVersion.isBlank()) {
            String loadedVersion = source != null ? source.getVersion() : null;
            if (loadedVersion == null || !loadedVersion.trim().equals(configVersion.trim())) {
                log.warn("Config version mismatch for tenant={} queue={}: requested={}, loaded={}", tenant, queueName, configVersion, loadedVersion);
                return null;
            }
        }
        PipelineConfiguration deepCopy = deepCopy(source);
        log.debug("Created LocalContext for tenant={} queue={} with deep copy of pipeline configuration", tenant, queueName);
        return new LocalContext(queueName, deepCopy);
    }

    /**
     * Returns a deep copy of the given pipeline configuration (via JSON round-trip).
     */
    public static PipelineConfiguration deepCopy(PipelineConfiguration config) {
        if (config == null) return null;
        String json = ExecutionTreeConfig.toJson(config);
        return ExecutionTreeConfig.fromJson(json);
    }

    /** Queue name this context was created for. */
    public String getQueueName() {
        return queueName;
    }

    /** Deep copy of the pipeline configuration (execution tree) for this queue. Safe to use and mutate locally. */
    public PipelineConfiguration getPipelineConfiguration() {
        return pipelineConfiguration;
    }
}
