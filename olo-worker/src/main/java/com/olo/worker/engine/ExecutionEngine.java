package com.olo.worker.engine;

import com.olo.executioncontext.ExecutionConfigSnapshot;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.plugin.PluginExecutor;

import java.util.Map;
import java.util.Objects;

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
     * @param dynamicNodeBuilder builder for fully designed dynamic nodes (from bootstrap); may be null
     * @param nodeFeatureEnricher enricher for attaching pipeline features to existing nodes; may be null (no-op)
     * @return workflow result string from ResultMapper
     */
    public static String run(
            ExecutionConfigSnapshot snapshot,
            Map<String, Object> inputValues,
            PluginExecutor pluginExecutor,
            Map<String, Object> tenantConfigMap,
            DynamicNodeBuilder dynamicNodeBuilder,
            NodeFeatureEnricher nodeFeatureEnricher) {
        Objects.requireNonNull(snapshot, "snapshot");
        return run(
                snapshot.getPipelineConfiguration(),
                null,
                snapshot.getQueueName(),
                inputValues,
                pluginExecutor,
                snapshot.getTenantId(),
                tenantConfigMap,
                snapshot.getRunId(),
                dynamicNodeBuilder,
                nodeFeatureEnricher);
    }

    /** Internal: run with optional ledger run id (for olo_run_node when ASYNC). */
    public static String run(
            PipelineConfiguration config,
            String entryPipelineName,
            String queueName,
            Map<String, Object> inputValues,
            PluginExecutor pluginExecutor,
            String tenantId,
            Map<String, Object> tenantConfigMap,
            String ledgerRunId,
            DynamicNodeBuilder dynamicNodeBuilder,
            NodeFeatureEnricher nodeFeatureEnricher) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(pluginExecutor, "pluginExecutor");
        Map<String, PipelineDefinition> pipelines = config.getPipelines();
        if (pipelines == null || pipelines.isEmpty()) throw new IllegalArgumentException("config has no pipelines");
        PipelineDefinition pipeline = entryPipelineName != null ? pipelines.get(entryPipelineName) : null;
        if (pipeline == null) pipeline = pipelines.values().iterator().next();
        return ExecutionEngineRunner.run(pipeline, queueName, inputValues, pluginExecutor, tenantId, tenantConfigMap,
                ledgerRunId, config, dynamicNodeBuilder, nodeFeatureEnricher);
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
     * @param dynamicNodeBuilder builder for fully designed dynamic nodes; may be null
     * @param nodeFeatureEnricher enricher for attaching pipeline features to existing nodes; may be null (no-op)
     * @return workflow result string from ResultMapper
     */
    public static String run(
            PipelineDefinition pipeline,
            String queueName,
            Map<String, Object> inputValues,
            PluginExecutor pluginExecutor,
            String tenantId,
            Map<String, Object> tenantConfigMap,
            DynamicNodeBuilder dynamicNodeBuilder,
            NodeFeatureEnricher nodeFeatureEnricher) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(pluginExecutor, "pluginExecutor");
        return ExecutionEngineRunner.run(pipeline, queueName, inputValues, pluginExecutor, tenantId, tenantConfigMap,
                null, null, dynamicNodeBuilder, nodeFeatureEnricher);
    }

    private ExecutionEngine() {
    }
}
