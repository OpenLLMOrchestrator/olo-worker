package com.olo.worker.engine;

import com.olo.executioncontext.ExecutionConfigSnapshot;
import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.plugin.PluginExecutor;

import com.olo.worker.engine.node.NodeExecutor;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Execution engine: orchestrates VariableEngine, NodeExecutor (node package), PluginInvoker, ResultMapper.
 * Single entry point to run the execution tree for a pipeline.
 * Prefer {@link #run(ExecutionConfigSnapshot, Map, PluginInvoker.PluginExecutor, Map)} for immutable snapshot and version pinning.
 */
public final class ExecutionEngine {

    /**
     * Runs the execution tree using an immutable config snapshot (no global config reads during run).
     *
     * @param snapshot         immutable snapshot (tenant, queue, config, version id)
     * @param inputValues      workflow input name to value (IN variables)
     * @param pluginExecutor   plugin executor (contract; from {@link com.olo.plugin.PluginExecutorFactory})
     * @param tenantConfigMap  tenant-specific config map; may be null or empty
     * @return workflow result string from ResultMapper
     */
    public static String run(
            ExecutionConfigSnapshot snapshot,
            Map<String, Object> inputValues,
            PluginExecutor pluginExecutor,
            Map<String, Object> tenantConfigMap) {
        Objects.requireNonNull(snapshot, "snapshot");
        return run(
                snapshot.getPipelineConfiguration(),
                null,
                snapshot.getQueueName(),
                inputValues,
                pluginExecutor,
                snapshot.getTenantId(),
                tenantConfigMap,
                snapshot.getRunId());
    }

    /** Internal: run with optional ledger run id so the executor thread can set LedgerContext (for olo_run_node when ASYNC). */
    public static String run(
            PipelineConfiguration config,
            String entryPipelineName,
            String queueName,
            Map<String, Object> inputValues,
            PluginExecutor pluginExecutor,
            String tenantId,
            Map<String, Object> tenantConfigMap,
            String ledgerRunId) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(pluginExecutor, "pluginExecutor");
        Map<String, PipelineDefinition> pipelines = config.getPipelines();
        if (pipelines == null || pipelines.isEmpty()) {
            throw new IllegalArgumentException("config has no pipelines");
        }
        PipelineDefinition pipeline = entryPipelineName != null ? pipelines.get(entryPipelineName) : null;
        if (pipeline == null) {
            pipeline = pipelines.values().iterator().next();
        }
        VariableEngine variableEngine = new VariableEngine(pipeline, inputValues);
        PluginInvoker pluginInvoker = new PluginInvoker(pluginExecutor);
        ExecutionType executionType = pipeline.getExecutionType();
        ExecutorService executor = executionType == ExecutionType.ASYNC
                ? Executors.newCachedThreadPool()
                : null;
        try {
            NodeExecutor nodeExecutor = new NodeExecutor(pluginInvoker, config, executionType, executor, tenantId, tenantConfigMap, ledgerRunId);
            ExecutionTreeNode root = pipeline.getExecutionTree();
            if (root != null) {
                // Single source of truth: in-memory tree for this run. Planner mutates it (attachChildren); loop finds next.
                RuntimeExecutionTree runtimeTree = new RuntimeExecutionTree(root);
                nodeExecutor.runWithTree(runtimeTree, pipeline, variableEngine, queueName != null ? queueName : "");
            }
            return ResultMapper.apply(pipeline, variableEngine);
        } finally {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Runs the execution tree for a single pipeline (SUB_PIPELINE nodes will no-op when no config is available).
     *
     * @param pipeline        pipeline definition
     * @param queueName       task queue name (for feature resolution, e.g. -debug)
     * @param inputValues     workflow input name to value (IN variables)
     * @param pluginExecutor  plugin executor (contract; from {@link com.olo.plugin.PluginExecutorFactory})
     * @param tenantId        tenant id (for feature context); may be null
     * @param tenantConfigMap tenant-specific config map; may be null or empty
     * @return workflow result string from ResultMapper
     */
    public static String run(
            PipelineDefinition pipeline,
            String queueName,
            Map<String, Object> inputValues,
            PluginExecutor pluginExecutor,
            String tenantId,
            Map<String, Object> tenantConfigMap) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(pluginExecutor, "pluginExecutor");
        VariableEngine variableEngine = new VariableEngine(pipeline, inputValues);
        PluginInvoker pluginInvoker = new PluginInvoker(pluginExecutor);
        ExecutionType executionType = pipeline.getExecutionType();
        ExecutorService executor = executionType == ExecutionType.ASYNC
                ? Executors.newCachedThreadPool()
                : null;
        try {
            NodeExecutor nodeExecutor = new NodeExecutor(pluginInvoker, null, executionType, executor, tenantId, tenantConfigMap);
            ExecutionTreeNode root = pipeline.getExecutionTree();
            if (root != null) {
                RuntimeExecutionTree runtimeTree = new RuntimeExecutionTree(root);
                nodeExecutor.runWithTree(runtimeTree, pipeline, variableEngine, queueName != null ? queueName : "");
            }
            return ResultMapper.apply(pipeline, variableEngine);
        } finally {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private ExecutionEngine() {
    }
}
