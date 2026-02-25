package com.olo.worker.engine.node;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.features.FeatureRegistry;
import com.olo.features.NodeExecutionContext;
import com.olo.features.ResolvedPrePost;
import com.olo.ledger.LedgerContext;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Single responsibility: execute one node (pre then execute then post).
 * Delegates activity detection to NodeActivityPredicate, pre/post to NodeFeatureRunner,
 * and type dispatch to NodeExecutionDispatcher.
 */
public final class NodeExecutor {

    private final ExecutionType executionType;
    private final ExecutorService executor;
    private final String tenantId;
    private final Map<String, Object> tenantConfigMap;
    private final String ledgerRunId;
    private final NodeFeatureRunner featureRunner;
    private final NodeExecutionDispatcher dispatcher;

    public NodeExecutor(PluginInvoker pluginInvoker, PipelineConfiguration config,
                        ExecutionType executionType, ExecutorService executor,
                        String tenantId, Map<String, Object> tenantConfigMap,
                        String ledgerRunId) {
        this.executionType = executionType != null ? executionType : ExecutionType.SYNC;
        this.executor = executor;
        this.tenantId = tenantId != null ? tenantId : "";
        this.tenantConfigMap = tenantConfigMap != null ? Map.copyOf(tenantConfigMap) : Map.of();
        this.ledgerRunId = ledgerRunId;
        this.featureRunner = new NodeFeatureRunner();
        this.dispatcher = new NodeExecutionDispatcher(pluginInvoker, config, executionType, executor, ledgerRunId);
    }

    /** Constructor without ledger run id (e.g. SUB_PIPELINE or single-pipeline run). */
    public NodeExecutor(PluginInvoker pluginInvoker, PipelineConfiguration config,
                        ExecutionType executionType, ExecutorService executor,
                        String tenantId, Map<String, Object> tenantConfigMap) {
        this(pluginInvoker, config, executionType, executor, tenantId, tenantConfigMap, null);
    }

    /**
     * Executes a single node only (no recursion). Used for per-node Temporal activities
     * so event history shows nodetype:plugin name. Runs pre, then node logic (PLUGIN = invoke;
     * SEQUENCE/IF/etc. = no-op), then post.
     */
    public void executeSingleNode(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName) {
        if (node == null) return;
        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
            LedgerContext.setRunId(ledgerRunId);
        }
        try {
            executeNodeSyncSingle(node, pipeline, variableEngine, queueName);
        } finally {
            if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                LedgerContext.clear();
            }
        }
    }

    private void executeNodeSyncSingle(ExecutionTreeNode node, PipelineDefinition pipeline,
                                       VariableEngine variableEngine, String queueName) {
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
        boolean isPlugin = node.getType() == NodeType.PLUGIN;
        String pluginId = isPlugin && node.getPluginRef() != null ? node.getPluginRef() : null;
        NodeExecutionContext context = new NodeExecutionContext(
                node.getId(), node.getType().getTypeName(), node.getNodeType(), null, tenantId, tenantConfigMap,
                queueName, pluginId, null);
        // Activity = executable leaf (node with no children and type that does work), not empty containers (SEQUENCE, GROUP, etc.).
        boolean isActivity = NodeActivityPredicate.isActivityNode(node);
        if (isActivity) {
            featureRunner.runPre(resolved, context, registry);
        }
        Object nodeResult = null;
        boolean executionSucceeded = false;
        try {
            nodeResult = dispatchExecuteSingle(node, pipeline, variableEngine, queueName);
            executionSucceeded = true;
            if (isActivity) {
                featureRunner.runPostSuccess(resolved, context.withExecutionSucceeded(true), nodeResult, registry);
            }
        } catch (Throwable t) {
            if (isActivity) {
                featureRunner.runPostError(resolved, context.withExecutionSucceeded(false), null, registry);
            }
            throw t;
        } finally {
            if (isActivity) {
                featureRunner.runFinally(resolved, context.withExecutionSucceeded(executionSucceeded), nodeResult, registry);
            }
        }
    }

    /** Runs this node's logic only (no recursion). Used for per-activity execution; supports any leaf type (PLUGIN, EVALUATION, REFLECTION, etc.). */
    private Object dispatchExecuteSingle(ExecutionTreeNode node, PipelineDefinition pipeline,
                                         VariableEngine variableEngine, String queueName) {
        return dispatcher.dispatch(node, pipeline, variableEngine, queueName, this::executeNode, this::executeNodeSync);
    }

    public void executeNode(ExecutionTreeNode node, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName) {
        if (node == null) return;
        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
            LedgerContext.setRunId(ledgerRunId);
        }
        try {
            // ASYNC applies to activity nodes only (executable leaves, e.g. PLUGIN); container nodes run sync.
            boolean runAsync = executionType == ExecutionType.ASYNC
                    && executor != null
                    && NodeActivityPredicate.isActivityNode(node)
                    && node.getType() != NodeType.JOIN;
            if (runAsync) {
                Future<?> future = executor.submit(() -> {
                    if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                        LedgerContext.setRunId(ledgerRunId);
                    }
                    try {
                        executeNodeSync(node, pipeline, variableEngine, queueName);
                    } finally {
                        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                            LedgerContext.clear();
                        }
                    }
                });
                try {
                    future.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                }
            } else {
                executeNodeSync(node, pipeline, variableEngine, queueName);
            }
        } finally {
            if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                LedgerContext.clear();
            }
        }
    }

    private void executeNodeSync(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName) {
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
        boolean isPlugin = node.getType() == NodeType.PLUGIN;
        String pluginId = isPlugin && node.getPluginRef() != null ? node.getPluginRef() : null;
        NodeExecutionContext context = new NodeExecutionContext(
                node.getId(), node.getType().getTypeName(), node.getNodeType(), null, tenantId, tenantConfigMap,
                queueName, pluginId, null);
        // Activity = executable leaf (node with no children and type that does work), not empty containers.
        boolean isActivity = NodeActivityPredicate.isActivityNode(node);
        if (isActivity) {
            featureRunner.runPre(resolved, context, registry);
        }
        Object nodeResult = null;
        boolean executionSucceeded = false;
        try {
            nodeResult = dispatcher.dispatch(node, pipeline, variableEngine, queueName, this::executeNode, this::executeNodeSync);
            executionSucceeded = true;
            if (isActivity) {
                featureRunner.runPostSuccess(resolved, context.withExecutionSucceeded(true), nodeResult, registry);
            }
        } catch (Throwable t) {
            if (isActivity) {
                featureRunner.runPostError(resolved, context.withExecutionSucceeded(false), null, registry);
            }
            throw t;
        } finally {
            if (isActivity) {
                featureRunner.runFinally(resolved, context.withExecutionSucceeded(executionSucceeded), nodeResult, registry);
            }
        }
    }
}
