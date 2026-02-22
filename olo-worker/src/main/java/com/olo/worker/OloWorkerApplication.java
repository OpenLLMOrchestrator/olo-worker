package com.olo.worker;

import com.olo.bootstrap.GlobalContext;
import com.olo.bootstrap.OloBootstrap;
import com.olo.config.OloSessionCache;
import com.olo.features.FeatureRegistry;
import com.olo.features.debug.DebuggerFeature;
import com.olo.plugin.ollama.OllamaModelExecutorPlugin;
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
        GlobalContext ctx = OloBootstrap.initialize();
        var config = ctx.getConfig();
        List<String> taskQueues = ctx.getTaskQueues();

        // Register plugins (e.g. Ollama for MODEL_EXECUTOR / GPT4_EXECUTOR in olo-chat-queue-oolama)
        String ollamaBaseUrl = System.getenv("OLLAMA_BASE_URL");
        String ollamaModel = System.getenv("OLLAMA_MODEL");
        OllamaModelExecutorPlugin.registerOllamaPlugin("GPT4_EXECUTOR", ollamaBaseUrl, ollamaModel);
        log.info("Registered Ollama model-executor plugin as GPT4_EXECUTOR (baseUrl={}, model={})",
                ollamaBaseUrl != null ? ollamaBaseUrl : "http://localhost:11434",
                ollamaModel != null ? ollamaModel : "llama3.2");

        FeatureRegistry.getInstance().register(new DebuggerFeature());
        log.info("Registered debug feature (pre/post logs when using -debug pipeline)");

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
        OloKernelActivitiesImpl oloKernelActivities = new OloKernelActivitiesImpl(sessionCache);

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
            factory.shutdown();
            try {
                factory.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.error("Error during worker shutdown: {}", ex.getMessage());
            }
        }
    }
}
