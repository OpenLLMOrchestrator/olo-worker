package com.olo.worker.engine;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.plugin.PluginExecutor;
import com.olo.worker.engine.node.NodeExecutor;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Single responsibility: run the execution tree for one pipeline (variable engine, executor, tree loop, result).
 */
final class ExecutionEngineRunner {

    private ExecutionEngineRunner() {
    }

    static String run(PipelineDefinition pipeline, String queueName, Map<String, Object> inputValues,
                      PluginExecutor pluginExecutor, String tenantId, Map<String, Object> tenantConfigMap,
                      String ledgerRunId, PipelineConfiguration config,
                      DynamicNodeBuilder dynamicNodeBuilder, NodeFeatureEnricher nodeFeatureEnricher) {
        VariableEngine variableEngine = new VariableEngine(pipeline, inputValues);
        PluginInvoker pluginInvoker = new PluginInvoker(pluginExecutor);
        ExecutionType executionType = pipeline.getExecutionType();
        ExecutorService executor = executionType == ExecutionType.ASYNC ? Executors.newCachedThreadPool() : null;
        try {
            NodeExecutor nodeExecutor = new NodeExecutor(pluginInvoker, config, executionType, executor,
                    tenantId, tenantConfigMap, ledgerRunId, dynamicNodeBuilder, nodeFeatureEnricher);
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
                    if (!executor.awaitTermination(5, TimeUnit.MINUTES)) executor.shutdownNow();
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
