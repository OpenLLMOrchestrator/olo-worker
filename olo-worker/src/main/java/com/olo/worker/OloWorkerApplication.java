package com.olo.worker;

import com.olo.annotations.ResourceCleanup;
import com.olo.bootstrap.BootstrapContext;
import com.olo.bootstrap.OloBootstrap;
import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.features.FeatureRegistry;
import com.olo.internal.features.InternalFeatures;
import com.olo.ledger.JdbcLedgerStore;
import com.olo.ledger.RunLedger;
import com.olo.internal.plugins.InternalPlugins;
import com.olo.plugin.PluginManager;
import com.olo.plugin.PluginProvider;
import com.olo.plugin.PluginRegistry;
import com.olo.worker.config.ConfigCompatibilityValidator;
import com.olo.worker.config.ConfigIncompatibleException;
import com.olo.worker.activity.ExecuteNodeDynamicActivity;
import com.olo.worker.activity.OloKernelActivitiesImpl;
import com.olo.worker.workflow.OloKernelWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OLO Temporal worker entry point. Task queues are taken from env OLO_QUEUE;
 * if OLO_IS_DEBUG_ENABLED=true, -debug variants are also registered.
 * <p>
 * WorkerFactory.start() returns immediately; the main thread is blocked so the JVM stays alive.
 * Shutdown hook and InterruptedException handle graceful shutdown (e.g. Ctrl+C).
 */
public final class OloWorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(OloWorkerApplication.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) {
        BootstrapContext ctx = OloBootstrap.initialize();
        var config = ctx.getConfig();
        List<String> taskQueues = ctx.getTaskQueues();

        List<String> tenantIds = ctx.getTenantIds();
        if (tenantIds.isEmpty()) {
            tenantIds = List.of(OloConfig.normalizeTenantId(null));
        }
        PluginManager pluginManager = InternalPlugins.createPluginManager();

        PluginRegistry registry = PluginRegistry.getInstance();
        int count = 0;
        // Internal plugins: any failure (isEnabled, getPlugin, duplicate id, contract mismatch) is fatal.
        // Register the provider (not getPlugin()) so the registry can create one instance per tree node.
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
        // Community plugins: failure is log-and-skip (unless OLO_PLUGINS_REQUIRED=true).
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

        startWorker(pluginManager, ctx);
    }

    /**
     * Starts the worker after plugins are loaded: registers features, ledger, validates config,
     * connects to Temporal, and runs the worker loop.
     */
    private static void startWorker(PluginManager pluginManager, BootstrapContext ctx) {
        var config = ctx.getConfig();
        List<String> taskQueues = ctx.getTaskQueues();

        RunLedger runLedger = null;
        if (config.isRunLedgerEnabled()) {
            com.olo.ledger.LedgerStore ledgerStore;
            try {
                JdbcLedgerStore jdbcStore = new JdbcLedgerStore(config);
                jdbcStore.ensureSchema();
                ledgerStore = jdbcStore;
            } catch (Exception e) {
                log.warn("Run ledger using no-op store: could not create or init JDBC store ({}). Execution continues.", e.getMessage());
                ledgerStore = new com.olo.ledger.NoOpLedgerStore();
            }
            runLedger = new RunLedger(ledgerStore);
        }

        OloSessionCache sessionCache = new OloSessionCache(config);
        InternalFeatures.registerInternalFeatures(FeatureRegistry.getInstance(), sessionCache, runLedger);

        validateAllPipelineConfigs(ctx);

        String temporalTarget = ctx.getTemporalTargetOrDefault("localhost:7233");
        String temporalNamespace = ctx.getTemporalNamespaceOrDefault("default");
        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalTarget)
                        .build()
        );
        WorkflowClient client = WorkflowClient.newInstance(
                service,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(temporalNamespace)
                        .build()
        );
        WorkerFactory factory = WorkerFactory.newInstance(client);

        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(10)
                .setMaxConcurrentWorkflowTaskExecutionSize(10)
                .build();

        List<String> tenantIdsForActivity = ctx.getTenantIds();
        if (tenantIdsForActivity.isEmpty()) {
            tenantIdsForActivity = List.of(OloConfig.normalizeTenantId(null));
        }
        OloKernelActivitiesImpl oloKernelActivities = new OloKernelActivitiesImpl(sessionCache, tenantIdsForActivity, runLedger);
        ExecuteNodeDynamicActivity executeNodeDynamicActivity = new ExecuteNodeDynamicActivity(oloKernelActivities);

        List<String> workflowTypesRegistered = new ArrayList<>();
        for (String taskQueue : taskQueues) {
            Worker worker = factory.newWorker(taskQueue, workerOptions);
            worker.registerWorkflowImplementationTypes(OloKernelWorkflowImpl.class);
            worker.registerActivitiesImplementations(oloKernelActivities, executeNodeDynamicActivity);
            log.info("Registered worker for task queue: {}", taskQueue);
        }
        workflowTypesRegistered.add("OloKernelWorkflow");

        log.info("Task queues registered: {}", taskQueues);
        log.info("Starting worker | Temporal: {} | namespace: {} | Cache: {}:{} | DB: {}:{}",
                temporalTarget, temporalNamespace,
                config.getCacheHost(), config.getCachePort(),
                config.getDbHost(), config.getDbPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down worker...");
            invokeResourceCleanup();
            factory.shutdown();
            try {
                factory.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error during worker shutdown: {}", e.getMessage());
            }
        }));

        factory.start();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Interrupted, shutting down worker...");
            invokeResourceCleanup();
            factory.shutdown();
            try {
                factory.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.error("Error during worker shutdown: {}", ex.getMessage());
            }
        }
    }

    /**
     * Invokes {@link ResourceCleanup#onExit()} on all registered plugins and features that implement it.
     * Called during shutdown before the worker factory is shut down.
     */
    private static void invokeResourceCleanup() {
        for (Map<String, PluginRegistry.PluginEntry> byId : PluginRegistry.getInstance().getAllByTenant().values()) {
            for (PluginRegistry.PluginEntry e : byId.values()) {
                Object p = e.getPlugin();
                if (p instanceof ResourceCleanup) {
                    try {
                        ((ResourceCleanup) p).onExit();
                    } catch (Exception ex) {
                        log.warn("Plugin {} onExit failed: {}", e.getId(), ex.getMessage());
                    }
                }
            }
        }
        for (FeatureRegistry.FeatureEntry e : FeatureRegistry.getInstance().getAll().values()) {
            Object inst = e.getInstance();
            if (inst instanceof ResourceCleanup) {
                try {
                    ((ResourceCleanup) inst).onExit();
                } catch (Exception ex) {
                    log.warn("Feature {} onExit failed: {}", e.getName(), ex.getMessage());
                }
            }
        }
    }

    /**
     * Validates all pipeline configs (config version, plugin contract version, feature contract version).
     * Called after plugins and features are registered so contract checks can run.
     * Fails startup if any config is incompatible (stops before breaking changes).
     */
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
}
