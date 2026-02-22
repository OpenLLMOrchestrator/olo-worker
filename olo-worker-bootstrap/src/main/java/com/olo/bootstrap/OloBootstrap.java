package com.olo.bootstrap;

import com.olo.config.OloConfig;
import com.olo.config.RedisPipelineConfigSourceSink;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.load.GlobalConfigurationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bootstrap for the OLO worker: loads configuration from environment and populates
 * the global pipeline configuration context for all configured task queues.
 * Returns a {@link GlobalContext} with in-memory config and queue → pipeline config map.
 */
public final class OloBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OloBootstrap.class);

    private OloBootstrap() {
    }

    /**
     * Creates configuration from environment, validates task queues, loads pipeline config
     * for each queue (Redis → DB → file → default; file-loaded config is written back to Redis),
     * and returns a global context with config and the map of queue → deserialized pipeline config.
     * Exits the JVM with code 1 if no task queues are configured.
     *
     * @return global context with {@link OloConfig} and map of queue name → {@link PipelineConfiguration}
     */
    public static GlobalContext initialize() {
        log.info("Bootstrap: loading configuration from environment");
        OloConfig config = OloConfig.fromEnvironment();
        List<String> taskQueues = config.getTaskQueues();
        if (taskQueues.isEmpty()) {
            log.error("No task queues configured. Set OLO_QUEUE (e.g. OLO_QUEUE=olo-chat-queue-oolama,olo-rag-queue-openai)");
            System.exit(1);
        }
        Path configDir = Path.of(config.getPipelineConfigDir());
        log.info("Bootstrap: configuration loaded from environment; taskQueues={}, configDir={}, version={}, configKeyPrefix={}, retryWaitSeconds={}",
                taskQueues, configDir.toAbsolutePath(), config.getPipelineConfigVersion(), config.getPipelineConfigKeyPrefix(), config.getPipelineConfigRetryWaitSeconds());
        log.info("Bootstrap: loading pipeline configuration for each queue (order: Redis → DB → <queue>.json → if -debug queue then <base>.json → default.json)");
        RedisPipelineConfigSourceSink configSourceSink = new RedisPipelineConfigSourceSink(config);
        GlobalConfigurationContext.loadAllQueuesAndPopulateContext(
                taskQueues,
                config.getPipelineConfigVersion(),
                configSourceSink,
                configSourceSink,
                configDir,
                config.getPipelineConfigRetryWaitSeconds(),
                config.getPipelineConfigKeyPrefix());
        log.info("Bootstrap: pipeline configuration loaded for all queues: {}", taskQueues);
        Map<String, PipelineConfiguration> pipelineConfigByQueue = new LinkedHashMap<>();
        GlobalConfigurationContext.getContextByQueue().forEach((queue, ctx) ->
                pipelineConfigByQueue.put(queue, ctx.getConfiguration()));
        return new GlobalContext(config, pipelineConfigByQueue);
    }
}
