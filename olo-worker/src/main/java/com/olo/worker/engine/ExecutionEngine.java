package com.olo.worker.engine;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Execution engine: orchestrates VariableEngine, FeatureResolver, NodeExecutor, PluginInvoker, ResultMapper.
 * Single entry point to run the execution tree for a pipeline.
 */
public final class ExecutionEngine {

    /**
     * Runs the execution tree using config and entry pipeline name (enables SUB_PIPELINE).
     *
     * @param config         full pipeline configuration (used for SUB_PIPELINE resolution)
     * @param entryPipelineName name of the pipeline to run (key in config.getPipelines()); if null or missing, first pipeline is used
     * @param queueName      task queue name (for feature resolution, e.g. -debug)
     * @param inputValues    workflow input name to value (IN variables)
     * @param pluginExecutor plugin invoker (e.g. activity::executePlugin + JSON)
     * @return workflow result string from ResultMapper
     */
    public static String run(
            PipelineConfiguration config,
            String entryPipelineName,
            String queueName,
            Map<String, Object> inputValues,
            PluginInvoker.PluginExecutor pluginExecutor) {
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
            NodeExecutor nodeExecutor = new NodeExecutor(pluginInvoker, config, executionType, executor);
            ExecutionTreeNode root = pipeline.getExecutionTree();
            if (root != null) {
                nodeExecutor.executeNode(root, pipeline, variableEngine, queueName != null ? queueName : "");
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
     * @param pipeline       pipeline definition (variableRegistry, inputContract, scope, executionTree, resultMapping)
     * @param queueName      task queue name (for feature resolution, e.g. -debug)
     * @param inputValues    workflow input name to value (IN variables)
     * @param pluginExecutor plugin invoker (e.g. activity::executePlugin + JSON)
     * @return workflow result string from ResultMapper
     */
    public static String run(
            PipelineDefinition pipeline,
            String queueName,
            Map<String, Object> inputValues,
            PluginInvoker.PluginExecutor pluginExecutor) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(pluginExecutor, "pluginExecutor");
        VariableEngine variableEngine = new VariableEngine(pipeline, inputValues);
        PluginInvoker pluginInvoker = new PluginInvoker(pluginExecutor);
        ExecutionType executionType = pipeline.getExecutionType();
        ExecutorService executor = executionType == ExecutionType.ASYNC
                ? Executors.newCachedThreadPool()
                : null;
        try {
            NodeExecutor nodeExecutor = new NodeExecutor(pluginInvoker, null, executionType, executor);
            ExecutionTreeNode root = pipeline.getExecutionTree();
            if (root != null) {
                nodeExecutor.executeNode(root, pipeline, variableEngine, queueName != null ? queueName : "");
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
