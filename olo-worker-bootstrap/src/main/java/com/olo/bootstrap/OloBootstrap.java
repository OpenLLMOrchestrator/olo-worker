package com.olo.bootstrap;

import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.config.RedisPipelineConfigSourceSink;
import com.olo.config.TenantConfigRegistry;
import com.olo.config.TenantEntry;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.load.GlobalConfigurationContext;
import com.olo.features.FeatureRegistry;
import com.olo.internal.features.InternalFeatures;
import com.olo.internal.plugins.InternalPlugins;
import com.olo.internal.tools.InternalTools;
import com.olo.ledger.JdbcLedgerStore;
import com.olo.ledger.LedgerStore;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.ledger.RunLedger;
import com.olo.planner.a.PlannerBootstrapContributor;
import com.olo.plugin.DefaultPluginExecutorFactory;
import com.olo.plugin.PluginManager;
import com.olo.plugin.PluginProvider;
import com.olo.plugin.PluginRegistry;
import com.olo.tools.PlannerToolDescriptor;
import com.olo.tools.ToolProvider;
import com.olo.bootstrap.validation.ConfigCompatibilityValidator;
import com.olo.bootstrap.validation.ConfigIncompatibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        return new BootstrapContextImpl(config, pipelineConfigByQueue, tenantIds);
    }

    /**
     * Full worker bootstrap: loads config and pipeline configs, registers plugins and tools,
     * runs bootstrap contributors (e.g. planner), creates run ledger and session cache,
     * registers features, and validates all pipeline configs. Returns a fully built context
     * so the worker only needs to start Temporal.
     *
     * @return worker bootstrap context with config, queues, tenants, ledger, and session cache
     */
    public static WorkerBootstrapContext initializeWorker() {
        BootstrapContextImpl ctx = (BootstrapContextImpl) initialize();
        OloConfig config = ctx.getConfig();
        List<String> tenantIds = ctx.getTenantIds();
        if (tenantIds.isEmpty()) {
            tenantIds = List.of(OloConfig.normalizeTenantId(null));
        }

        PluginManager pluginManager = InternalPlugins.createPluginManager();
        InternalTools.registerInternalTools(pluginManager);

        PluginRegistry registry = PluginRegistry.getInstance();
        int count = 0;
        for (PluginProvider provider : pluginManager.getInternalProviders()) {
            if (!provider.isEnabled()) continue;
            String id = provider.getPluginId();
            String contractType = provider.getContractType();
            String version = provider.getVersion();
            Map<String, Object> capabilityMetadata = provider.getCapabilityMetadata();
            for (String tenantId : tenantIds) {
                registry.register(tenantId, id, contractType, version, capabilityMetadata, provider);
            }
            count++;
            log.info("Registered plugin {} (contractType={}, version={}) for {} tenant(s)", id, contractType, version, tenantIds.size());
        }
        for (PluginProvider provider : pluginManager.getCommunityProviders()) {
            try {
                if (!provider.isEnabled()) continue;
                String id = provider.getPluginId();
                String contractType = provider.getContractType();
                String version = provider.getVersion();
                Map<String, Object> capabilityMetadata = provider.getCapabilityMetadata();
                for (String tenantId : tenantIds) {
                    registry.register(tenantId, id, contractType, version, capabilityMetadata, provider);
                }
                count++;
                log.info("Registered plugin {} (contractType={}, version={}) for {} tenant(s)", id, contractType, version, tenantIds.size());
            } catch (Exception e) {
                log.error("Community plugin failed to register (skipping): pluginId={}, error={}",
                        provider != null ? provider.getPluginId() : "?", e.getMessage(), e);
            }
        }
        if (count == 0) {
            log.warn("No plugins registered; check internal providers and OLO_PLUGINS_DIR for community JARs");
        }

        com.olo.planner.a.PlannerARegistration.registerSubtreeCreatorPlugin(registry, tenantIds);
        log.info("Registered subtree creator plugin {} for {} tenant(s)", com.olo.planner.a.PlannerARegistration.DEFAULT_JSON_SUBTREE_CREATOR, tenantIds.size());

        runBootstrapContributors(ctx, pluginManager);

        RunLedger runLedger = null;
        if (config.isRunLedgerEnabled()) {
            try {
                JdbcLedgerStore jdbcStore = new JdbcLedgerStore(config);
                jdbcStore.ensureSchema();
                LedgerStore ledgerStore = jdbcStore;
                runLedger = new RunLedger(ledgerStore);
                log.info("Run ledger: JDBC store enabled; run and node records will be persisted to olo_run, olo_config, olo_run_node.");
            } catch (Exception e) {
                log.warn("Run ledger using no-op store: could not create or init JDBC store ({}). Execution continues. No ledger entries will be persisted.", e.getMessage());
                runLedger = new RunLedger(new NoOpLedgerStore());
            }
        } else {
            log.info("Run ledger disabled (OLO_RUN_LEDGER=false or unset). Set OLO_RUN_LEDGER=true for run/node persistence.");
        }

        OloSessionCache sessionCache = new OloSessionCache(config);
        InternalFeatures.registerInternalFeatures(FeatureRegistry.getInstance(), sessionCache, runLedger);

        validateAllPipelineConfigs(ctx);

        com.olo.plugin.PluginExecutorFactory pluginExecutorFactory = new DefaultPluginExecutorFactory();
        com.olo.node.DynamicNodeBuilder dynamicNodeBuilder = com.olo.bootstrap.node.PipelineDynamicNodeBuilder.getInstance();
        com.olo.node.NodeFeatureEnricherFactory nodeFeatureEnricherFactory = com.olo.bootstrap.node.DefaultNodeFeatureEnricherFactory.getInstance();
        return new WorkerBootstrapContextImpl(ctx, runLedger, sessionCache, pluginExecutorFactory, dynamicNodeBuilder, nodeFeatureEnricherFactory);
    }

    private static void runBootstrapContributors(BootstrapContextImpl ctx, PluginManager pluginManager) {
        List<BootstrapContributor> contributors = new ArrayList<>();
        for (PluginProvider p : pluginManager.getInternalProviders()) {
            if (p instanceof BootstrapContributor) {
                contributors.add((BootstrapContributor) p);
            }
        }
        for (PluginProvider p : pluginManager.getCommunityProviders()) {
            if (p instanceof BootstrapContributor) {
                contributors.add((BootstrapContributor) p);
            }
        }
        List<PlannerToolDescriptor> toolDescriptors = pluginManager.getInternalProviders().stream()
                .filter(p -> p instanceof ToolProvider)
                .map(p -> (PlannerToolDescriptor) p)
                .collect(Collectors.toList());
        contributors.add(new PlannerBootstrapContributor(toolDescriptors));
        for (BootstrapContributor c : contributors) {
            c.contribute(ctx);
        }
    }

    private static void validateAllPipelineConfigs(BootstrapContext ctx) {
        ConfigCompatibilityValidator validator = new ConfigCompatibilityValidator(
                null, null,
                PluginRegistry.getInstance(),
                FeatureRegistry.getInstance());
        for (Map.Entry<String, PipelineConfiguration> e : ctx.getPipelineConfigByQueue().entrySet()) {
            String key = e.getKey();
            String tenantId = key.indexOf(':') >= 0 ? key.substring(0, key.indexOf(':')) : key;
            try {
                validator.validateOrThrow(tenantId, e.getValue());
            } catch (ConfigIncompatibleException ex) {
                log.error("Config compatibility check failed at bootstrap: {}", ex.getMessage());
                throw ex;
            }
        }
        log.info("Config version checks passed for all pipeline configs");
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
