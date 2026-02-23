package com.olo.worker;

import com.olo.annotations.ResourceCleanup;
import com.olo.bootstrap.BootstrapContext;
import com.olo.bootstrap.OloBootstrap;
import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.features.FeatureRegistry;
import com.olo.features.debug.DebuggerFeature;
import com.olo.features.metrics.MetricsFeature;
import com.olo.features.quota.QuotaContext;
import com.olo.features.quota.QuotaFeature;
import com.olo.ledger.JdbcLedgerStore;
import com.olo.ledger.NodeLedgerFeature;
import com.olo.ledger.RunLedger;
import com.olo.ledger.RunLevelLedgerFeature;
import com.olo.plugin.PluginRegistry;
import com.olo.plugin.ollama.OllamaModelExecutorPlugin;
import com.olo.worker.config.ConfigCompatibilityValidator;
import com.olo.worker.config.ConfigIncompatibleException;
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

        // Register Ollama plugin for each tenant (e.g. MODEL_EXECUTOR / GPT4_EXECUTOR in olo-chat-queue-oolama)
        String ollamaBaseUrl = System.getenv("OLLAMA_BASE_URL");
        String ollamaModel = System.getenv("OLLAMA_MODEL");
        if (ollamaModel == null || ollamaModel.isBlank()) {
            ollamaModel = "llama3.2";
        }
        if (ollamaBaseUrl == null || ollamaBaseUrl.isBlank()) {
            ollamaBaseUrl = "http://localhost:11434";
        }
        OllamaModelExecutorPlugin plugin = new OllamaModelExecutorPlugin(ollamaBaseUrl, ollamaModel);
        List<String> tenantIds = ctx.getTenantIds();
        if (tenantIds.isEmpty()) {
            tenantIds = List.of(OloConfig.normalizeTenantId(null));
        }
        for (String tenantId : tenantIds) {
            plugin.register(tenantId, "GPT4_EXECUTOR");
        }
        log.info("Registered Ollama model-executor plugin as GPT4_EXECUTOR for {} tenant(s) (baseUrl={}, model={})",
                tenantIds.size(), ollamaBaseUrl, ollamaModel);

        FeatureRegistry.getInstance().register(new DebuggerFeature());
        log.info("Registered debug feature (pre/post logs when using -debug pipeline)");
        FeatureRegistry.getInstance().register(new QuotaFeature());
        log.info("Registered quota feature (PRE, fail-fast on soft/hard limit from tenant config)");
        FeatureRegistry.getInstance().register(new MetricsFeature());
        log.info("Registered metrics feature (PRE_FINALLY, lazy MeterRegistry, olo.node.executions counter)");

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
            FeatureRegistry.getInstance().register(new RunLevelLedgerFeature());
            FeatureRegistry.getInstance().register(new NodeLedgerFeature(runLedger));
            log.info("Registered run ledger features (ledger-run on root, ledger-node on every node); OLO_RUN_LEDGER=true");
        } else {
            log.debug("Run ledger disabled (OLO_RUN_LEDGER=false); no ledger features registered");
        }

        // Version checks (config, plugin contract, feature contract) run at bootstrap / config load
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

        OloSessionCache sessionCache = new OloSessionCache(config);
        QuotaContext.setSessionCache(sessionCache);
        List<String> tenantIdsForActivity = ctx.getTenantIds();
        if (tenantIdsForActivity.isEmpty()) {
            tenantIdsForActivity = List.of(OloConfig.normalizeTenantId(null));
        }
        OloKernelActivitiesImpl oloKernelActivities = new OloKernelActivitiesImpl(sessionCache, tenantIdsForActivity, runLedger);

        List<String> workflowTypesRegistered = new ArrayList<>();
        for (String taskQueue : taskQueues) {
            Worker worker = factory.newWorker(taskQueue, workerOptions);
            worker.registerWorkflowImplementationTypes(OloKernelWorkflowImpl.class);
            worker.registerActivitiesImplementations(oloKernelActivities);
            log.info("Registered worker for task queue: {}", taskQueue);
        }
        workflowTypesRegistered.add("OloKernelWorkflow");

        log.info("Task queues registered: {}", taskQueues);
        if (workflowTypesRegistered.isEmpty()) {
            log.info("Workflow types registered: (none - register workflow implementations in the loop above)");
        } else {
            log.info("Workflow types registered: {}", workflowTypesRegistered);
        }
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
