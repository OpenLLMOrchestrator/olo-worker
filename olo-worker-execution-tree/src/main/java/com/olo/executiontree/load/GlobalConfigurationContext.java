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
 * Global registry of read-only pipeline configuration per queue.
 * Populated at bootstrap by {@link #loadAllQueuesAndPopulateContext(List, String, ConfigSource, Path, int, String)}
 * or the overload that accepts {@link ConfigSink} (to persist file-loaded config to Redis and DB).
 */
public final class GlobalConfigurationContext {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigurationContext.class);
    private static final Map<String, GlobalContext> CONTEXT_BY_QUEUE = new ConcurrentHashMap<>();

    private GlobalConfigurationContext() {
    }

    /**
     * Returns an unmodifiable view of the map from queue name to global context.
     * Empty until {@link #loadAllQueuesAndPopulateContext} is called.
     */
    public static Map<String, GlobalContext> getContextByQueue() {
        return Collections.unmodifiableMap(CONTEXT_BY_QUEUE);
    }

    /**
     * Returns the global context for the given queue, or null if not loaded.
     */
    public static GlobalContext get(String queueName) {
        return CONTEXT_BY_QUEUE.get(queueName);
    }

    /**
     * Registers a read-only configuration for a queue (e.g. after loading).
     */
    public static void put(String queueName, PipelineConfiguration configuration) {
        CONTEXT_BY_QUEUE.put(queueName, new GlobalContext(queueName, configuration));
    }

    /**
     * Loads configuration for each configured queue and stores a read-only copy in the global context map.
     * For each queue: tries Redis → DB → local queue config file → default file; if none found, waits
     * retryWaitSeconds and retries until a valid configuration is found.
     * Does not persist file-loaded config to Redis/DB (use the overload with ConfigSink for that).
     *
     * @param queueNames       list of task queue names (e.g. from OLO_QUEUE)
     * @param version          config version (e.g. 1.0)
     * @param configSource     source for Redis and DB
     * @param configDir        directory for local config files (e.g. config/)
     * @param retryWaitSeconds seconds to wait before retrying when no config is found
     * @param configKeyPrefix  prefix for Redis key (e.g. olo:engine:config); null = default
     */
    public static void loadAllQueuesAndPopulateContext(
            List<String> queueNames,
            String version,
            ConfigSource configSource,
            Path configDir,
            int retryWaitSeconds,
            String configKeyPrefix) {
        loadAllQueuesAndPopulateContext(queueNames, version, configSource, null, configDir, retryWaitSeconds, configKeyPrefix);
    }

    /**
     * Loads configuration for each configured queue and stores a read-only copy in the global context map.
     * When config is loaded from a local file (queue or default), it is written back to Redis and DB via
     * {@code configSink} so other containers can use it.
     *
     * @param queueNames       list of task queue names (e.g. from OLO_QUEUE)
     * @param version          config version (e.g. 1.0)
     * @param configSource     source for Redis and DB
     * @param configSink       when non-null, config loaded from file is persisted to Redis and DB for other containers
     * @param configDir        directory for local config files (e.g. config/)
     * @param retryWaitSeconds seconds to wait before retrying when no config is found
     * @param configKeyPrefix  prefix for Redis key (e.g. olo:engine:config); null = default
     */
    public static void loadAllQueuesAndPopulateContext(
            List<String> queueNames,
            String version,
            ConfigSource configSource,
            ConfigSink configSink,
            Path configDir,
            int retryWaitSeconds,
            String configKeyPrefix) {
        if (queueNames == null || queueNames.isEmpty()) {
            return;
        }
        ConfigurationLoader loader = new ConfigurationLoader(
                configSource, configSink, configDir, retryWaitSeconds, configKeyPrefix);
        for (String queueName : queueNames) {
            log.info("Loading pipeline configuration for queue={} version={}", queueName, version);
            PipelineConfiguration config = loader.loadConfiguration(queueName, version);
            put(queueName, config);
        }
    }
}
