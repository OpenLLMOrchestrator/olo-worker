package com.olo.worker;

import com.olo.bootstrap.OloBootstrap;
import com.olo.bootstrap.WorkerBootstrapContext;
import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.ledger.RunLedger;
import com.olo.plugin.PluginExecutorFactory;
import com.olo.worker.activity.ExecuteNodeDynamicActivity;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.worker.activity.impl.OloKernelActivitiesImpl;
import com.olo.worker.workflow.impl.OloKernelWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OLO Temporal worker entry point. Requests a fully built bootstrap context from
 * {@link OloBootstrap#initializeWorker()} then starts the Temporal worker loop.
 * No bootstrap logic (plugin registration, contributors, validation) runs here.
 */
public final class OloWorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(OloWorkerApplication.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) {
        WorkerBootstrapContext ctx = OloBootstrap.initializeWorker();

        OloConfig config = ctx.getConfig();
        List<String> taskQueues = ctx.getTaskQueues();
        List<String> tenantIds = ctx.getTenantIds();
        if (tenantIds.isEmpty()) {
            tenantIds = List.of(OloConfig.normalizeTenantId(null));
        }

        RunLedger runLedger = ctx.getRunLedger() instanceof RunLedger ? (RunLedger) ctx.getRunLedger() : null;
        if (runLedger == null) {
            log.warn("Run ledger is null from bootstrap (OLO_RUN_LEDGER=false or unset). Using no-op ledger so runId is set and node ledger path runs; no rows will be persisted. Set OLO_RUN_LEDGER=true and ensure DB is reachable for olo_run/olo_config/olo_run_node.");
            runLedger = new RunLedger(new NoOpLedgerStore());
        }
        OloSessionCache sessionCache = (OloSessionCache) ctx.getSessionCache();

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

        PluginExecutorFactory pluginExecutorFactory = ctx.getPluginExecutorFactory();
        var dynamicNodeBuilder = ctx.getDynamicNodeBuilder();
        var nodeFeatureEnricher = ctx.getNodeFeatureEnricherFactory().getEnricher();
        OloKernelActivitiesImpl oloKernelActivities = new OloKernelActivitiesImpl(sessionCache, tenantIds, runLedger, pluginExecutorFactory, dynamicNodeBuilder, nodeFeatureEnricher);
        ExecuteNodeDynamicActivity executeNodeDynamicActivity = new ExecuteNodeDynamicActivity(oloKernelActivities);

        for (String taskQueue : taskQueues) {
            Worker worker = factory.newWorker(taskQueue, workerOptions);
            worker.registerWorkflowImplementationTypes(OloKernelWorkflowImpl.class);
            worker.registerActivitiesImplementations(oloKernelActivities, executeNodeDynamicActivity);
            log.info("Registered worker for task queue: {}", taskQueue);
        }

        log.info("Task queues registered: {}", taskQueues);
        log.info("Starting worker | Temporal: {} | namespace: {} | Cache: {}:{} | DB: {}:{}",
                temporalTarget, temporalNamespace,
                config.getCacheHost(), config.getCachePort(),
                config.getDbHost(), config.getDbPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down worker...");
            ctx.runResourceCleanup();
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
            ctx.runResourceCleanup();
            factory.shutdown();
            try {
                factory.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.error("Error during worker shutdown: {}", ex.getMessage());
            }
        }
    }

}
