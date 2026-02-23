package com.olo.bootstrap;

import com.olo.config.OloConfig;
import com.olo.config.RedisPipelineConfigSourceSink;
import com.olo.config.TenantConfigRegistry;
import com.olo.config.TenantEntry;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.load.GlobalConfigurationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bootstrap for the OLO worker: loads configuration from environment and populates
 * {@link com.olo.executiontree.load.GlobalConfigurationContext} (runtime config store) for all
 * configured tenants and task queues. Returns a {@link BootstrapContext} wrapper with
 * in-memory config and a flattened map of pipeline configs for validation.
 */
public final class OloBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OloBootstrap.class);

    private OloBootstrap() {
    }

    /**
     * Creates configuration from environment, validates task queues, loads pipeline config
     * per tenant (Redis → DB → file → default; file-loaded config is written back to Redis),
     * and returns a bootstrap context with config and the map of "tenant:queue" → pipeline config.
     * Exits the JVM with code 1 if no task queues are configured.
     *
     * @return bootstrap context with {@link OloConfig} and map of composite key → {@link PipelineConfiguration}
     */
    public static BootstrapContext initialize() {
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
        log.info("Bootstrap: loading pipeline configuration per tenant (order: Redis → DB → <queue>.json → if -debug queue then <base>.json → default.json)");
        RedisPipelineConfigSourceSink configSourceSink = new RedisPipelineConfigSourceSink(config);
        List<String> tenantIds = resolveTenantIds(configSourceSink, config);
        if (tenantIds.isEmpty()) tenantIds = List.of(OloConfig.normalizeTenantId(null));
        for (String tenantId : tenantIds) {
            String tenantScopedPrefix = config.getPipelineConfigKeyPrefix(tenantId);
            GlobalConfigurationContext.loadAllQueuesAndPopulateContext(
                    tenantId,
                    taskQueues,
                    config.getPipelineConfigVersion(),
                    configSourceSink,
                    configSourceSink,
                    configDir,
                    config.getPipelineConfigRetryWaitSeconds(),
                    tenantScopedPrefix);
        }
        log.info("Bootstrap: pipeline configuration loaded for tenants={} queues={}", tenantIds, taskQueues);
        Map<String, PipelineConfiguration> pipelineConfigByQueue = new LinkedHashMap<>();
        GlobalConfigurationContext.getContextByTenantAndQueue().forEach((tenant, byQueue) ->
                byQueue.forEach((queue, ctx) ->
                        pipelineConfigByQueue.put(tenant + ":" + queue, ctx.getConfiguration())));
        return new BootstrapContext(config, pipelineConfigByQueue, tenantIds);
    }

    /**
     * Resolves the list of tenant ids to load config for: Redis key {@link TenantEntry#REDIS_TENANTS_KEY}
     * (JSON array of {@code {"id":"...","name":"..."}}). If not available or invalid, uses {@link OloConfig#getTenantIds()} from env (OLO_TENANT_IDS).
     */
    private static List<String> resolveTenantIds(RedisPipelineConfigSourceSink configSourceSink, OloConfig config) {
        try {
            var opt = configSourceSink.getFromCache(TenantEntry.REDIS_TENANTS_KEY);
            String json = opt.orElse("");
            List<TenantEntry.TenantEntryWithConfig> entries = TenantEntry.parseTenantEntriesWithConfig(json);
            if (!entries.isEmpty()) {
                TenantConfigRegistry registry = TenantConfigRegistry.getInstance();
                List<String> ids = new ArrayList<>();
                Set<String> seen = new java.util.LinkedHashSet<>();
                for (TenantEntry.TenantEntryWithConfig e : entries) {
                    registry.put(e.getId(), e.getConfig());
                    if (seen.add(e.getId())) ids.add(e.getId());
                }
                log.info("Bootstrap: using tenant list from Redis {} ({} tenant(s), tenant config loaded)", TenantEntry.REDIS_TENANTS_KEY, ids.size());
                return ids;
            }
            List<String> fromRedis = TenantEntry.parseTenantIds(json);
            if (!fromRedis.isEmpty()) {
                log.info("Bootstrap: using tenant list from Redis {} ({} tenant(s))", TenantEntry.REDIS_TENANTS_KEY, fromRedis.size());
                return fromRedis;
            }
        } catch (Exception e) {
            log.debug("Bootstrap: could not read {} from Redis, using OLO_TENANT_IDS: {}", TenantEntry.REDIS_TENANTS_KEY, e.getMessage());
        }
        List<String> fromEnv = config.getTenantIds();
        if (fromEnv.isEmpty()) {
            fromEnv = List.of(OloConfig.normalizeTenantId(null));
        }
        if (!fromEnv.isEmpty()) {
            log.info("Bootstrap: using tenant list from env OLO_TENANT_IDS: {}", fromEnv);
            try {
                String json = TenantEntry.toJsonArray(fromEnv);
                configSourceSink.putInCache(TenantEntry.REDIS_TENANTS_KEY, json);
                log.info("Bootstrap: created {} with {} tenant(s) from env (key was missing or empty)", TenantEntry.REDIS_TENANTS_KEY, fromEnv.size());
            } catch (Exception e) {
                log.warn("Bootstrap: could not write {} to Redis: {}", TenantEntry.REDIS_TENANTS_KEY, e.getMessage());
            }
        }
        return fromEnv;
    }
}
