package com.olo.executiontree.load;

import com.olo.executiontree.config.PipelineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of read-only pipeline configuration per tenant and queue.
 * Structure: Map&lt;tenantKey, Map&lt;queueName, GlobalContext&gt;&gt;.
 * Populated at bootstrap by {@link #loadAllQueuesAndPopulateContext(String, List, String, ConfigSource, Path, int, String)}
 * (or overloads). All Redis keys and DB data are scoped by tenant (olo:&lt;tenantId&gt;:...).
 */
public final class GlobalConfigurationContext {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigurationContext.class);
    /** tenantKey -> (queueName -> GlobalContext) */
    private static final Map<String, Map<String, GlobalContext>> CONTEXT_BY_TENANT_AND_QUEUE = new ConcurrentHashMap<>();

    private GlobalConfigurationContext() {
    }

    /**
     * Returns an unmodifiable view: tenantKey → (queueName → global context).
     * Empty until {@link #loadAllQueuesAndPopulateContext} is called.
     */
    public static Map<String, Map<String, GlobalContext>> getContextByTenantAndQueue() {
        Map<String, Map<String, GlobalContext>> copy = new ConcurrentHashMap<>();
        CONTEXT_BY_TENANT_AND_QUEUE.forEach((tenant, byQueue) ->
                copy.put(tenant, Collections.unmodifiableMap(new ConcurrentHashMap<>(byQueue))));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the global context for the given tenant and queue, or null if not loaded.
     */
    public static GlobalContext get(String tenantKey, String queueName) {
        Map<String, GlobalContext> byQueue = CONTEXT_BY_TENANT_AND_QUEUE.get(tenantKey);
        return byQueue != null ? byQueue.get(queueName) : null;
    }

    /**
     * Registers a read-only configuration for a tenant and queue (e.g. after loading).
     */
    public static void put(String tenantKey, String queueName, PipelineConfiguration configuration) {
        CONTEXT_BY_TENANT_AND_QUEUE
                .computeIfAbsent(tenantKey, k -> new ConcurrentHashMap<>())
                .put(queueName, new GlobalContext(queueName, configuration));
    }

    /**
     * Loads configuration for each configured queue for the given tenant and stores in the global context.
     * For each queue: tries Redis → DB → local queue config file → default file; if none found, waits
     * retryWaitSeconds and retries until a valid configuration is found.
     * Does not persist file-loaded config to Redis/DB (use the overload with ConfigSink for that).
     *
     * @param tenantKey        tenant id (used for Redis key prefix and DB; use {@link com.olo.config.OloConfig#normalizeTenantId(String)})
     * @param queueNames       list of task queue names (e.g. from OLO_QUEUE)
     * @param version          config version (e.g. 1.0)
     * @param configSource     source for Redis and DB
     * @param configDir        directory for local config files (e.g. config/)
     * @param retryWaitSeconds seconds to wait before retrying when no config is found
     * @param configKeyPrefix  tenant-scoped prefix for Redis key (e.g. olo:&lt;tenantId&gt;:kernel:config); null = default
     */
    public static void loadAllQueuesAndPopulateContext(
            String tenantKey,
            List<String> queueNames,
            String version,
            ConfigSource configSource,
            Path configDir,
            int retryWaitSeconds,
            String configKeyPrefix) {
        loadAllQueuesAndPopulateContext(tenantKey, queueNames, version, configSource, null, configDir, retryWaitSeconds, configKeyPrefix);
    }

    /**
     * Loads configuration for each configured queue for the given tenant and stores in the global context.
     * When config is loaded from a local file (queue or default), it is written back to Redis and DB via
     * {@code configSink} so other containers can use it.
     *
     * @param tenantKey        tenant id (used for Redis key prefix and DB)
     * @param queueNames       list of task queue names (e.g. from OLO_QUEUE)
     * @param version          config version (e.g. 1.0)
     * @param configSource     source for Redis and DB
     * @param configSink       when non-null, config loaded from file is persisted to Redis and DB for other containers
     * @param configDir        directory for local config files (e.g. config/)
     * @param retryWaitSeconds seconds to wait before retrying when no config is found
     * @param configKeyPrefix  tenant-scoped prefix for Redis key (e.g. olo:&lt;tenantId&gt;:kernel:config); null = default
     */
    public static void loadAllQueuesAndPopulateContext(
            String tenantKey,
            List<String> queueNames,
            String version,
            ConfigSource configSource,
            ConfigSink configSink,
            Path configDir,
            int retryWaitSeconds,
            String configKeyPrefix) {
        if (tenantKey == null || tenantKey.isBlank() || queueNames == null || queueNames.isEmpty()) {
            return;
        }
        ConfigurationLoader loader = new ConfigurationLoader(
                configSource, configSink, configDir, retryWaitSeconds, configKeyPrefix);
        for (String queueName : queueNames) {
            log.info("Loading pipeline configuration for tenant={} queue={} version={}", tenantKey, queueName, version);
            PipelineConfiguration config = loader.loadConfiguration(tenantKey, queueName, version);
            put(tenantKey, queueName, config);
        }
    }
}
