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
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Single responsibility: execute one node (pre then execute then post).
 * Delegates activity detection to NodeActivityPredicate, pre/post to NodeFeatureRunner,
 * and type dispatch to NodeExecutionDispatcher.
 */
public final class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

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
     * Tree-driven execution: single source of truth. One loop, no special cases.
     * <pre>
     *   while (true) {
     *     nextId = tree.findNextExecutable();
     *     if (nextId == null) break;
     *     dispatch(node);   // PLANNER only mutates tree (attachChildren); never runs children here
     *     tree.markCompleted(nextId);
     *   }
     * </pre>
     * Do NOT execute planner children inline — always return to this loop so findNextExecutable picks them.
     */
    public void runWithTree(RuntimeExecutionTree runtimeTree, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName) {
        if (runtimeTree == null || runtimeTree.getRootId() == null) return;
        if (log.isInfoEnabled()) {
            log.info("Tree loop started | rootId={}", runtimeTree.getRootId());
        }
        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
            LedgerContext.setRunId(ledgerRunId);
        }
        try {
            int iteration = 0;
            while (true) {
                iteration++;
                if (log.isInfoEnabled()) {
                    log.info("Tree loop step 1 | iteration={} | findNextExecutable", iteration);
                }
                String nextId = runtimeTree.findNextExecutable();
                if (nextId == null) {
                    if (log.isInfoEnabled()) {
                        log.info("Tree loop step 2 | iteration={} | no more executable nodes | loop finished", iteration);
                    }
                    break;
                }
                ExecutionTreeNode node = runtimeTree.getDefinition(nextId);
                if (node != null && log.isInfoEnabled()) {
                    log.info("Tree loop step 3 | iteration={} | nextId={} type={} displayName={} | executing", iteration, nextId, node.getType(), node.getDisplayName());
                }
                if (node == null) {
                    if (log.isInfoEnabled()) log.info("Tree loop step 4 | node definition null for id={} | markCompleted and continue", nextId);
                    runtimeTree.markCompleted(nextId);
                    continue;
                }
                if (log.isInfoEnabled()) {
                    log.info("Tree loop step 5 | runOneNodeInTree | nodeId={} type={}", nextId, node.getType());
                }
                runOneNodeInTree(node, pipeline, variableEngine, queueName, runtimeTree);
                runtimeTree.markCompleted(nextId);
                if (log.isInfoEnabled()) {
                    log.info("Tree loop step 6 | markCompleted | nodeId={} | iteration={}", nextId, iteration);
                }
            }
        } finally {
            if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                LedgerContext.clear();
            }
        }
    }

    /**
     * Runs the PLANNER node only (model + parse + inject variables). Returns the list of step nodes
     * without running them. Used when each step is scheduled as a separate Temporal activity.
     */
    public List<ExecutionTreeNode> executePlannerOnly(ExecutionTreeNode node, PipelineDefinition pipeline,
                                                      VariableEngine variableEngine, String queueName) {
        if (node == null || node.getType() != NodeType.PLANNER) return List.of();
        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
            LedgerContext.setRunId(ledgerRunId);
        }
        try {
            FeatureRegistry registry = FeatureRegistry.getInstance();
            ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
            NodeExecutionContext context = new NodeExecutionContext(
                    node.getId(), node.getType().getTypeName(), node.getNodeType(), null, tenantId, tenantConfigMap,
                    queueName, null, null);
            featureRunner.runPre(resolved, context, registry);
            List<ExecutionTreeNode> steps = dispatcher.runPlannerReturnSteps(node, pipeline, variableEngine, queueName);
            featureRunner.runPostSuccess(resolved, context.withExecutionSucceeded(true), null, registry);
            return steps;
        } catch (Throwable t) {
            log.warn("Planner-only execution failed: nodeId={} error={}", node.getId(), t.getMessage(), t);
            FeatureRegistry registry = FeatureRegistry.getInstance();
            ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
            NodeExecutionContext context = new NodeExecutionContext(
                    node.getId(), node.getType().getTypeName(), node.getNodeType(), null, tenantId, tenantConfigMap,
                    queueName, null, null);
            featureRunner.runPostError(resolved, context.withExecutionSucceeded(false), null, registry);
            throw t;
        } finally {
            if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                LedgerContext.clear();
            }
        }
    }

    /**
     * Executes a single node only (no recursion). Used for per-node Temporal activities
     * and for dynamic (planner-generated) steps. Runs pre, then node logic (PLUGIN = invoke;
     * SEQUENCE/IF/etc. = no-op), then post.
     * <p>
     * PLANNER nodes must not be executed here; the activity uses {@link #executePlannerOnly} for PLANNER
     * and then schedules each returned step as a separate activity.
     */
    public void executeSingleNode(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName) {
        if (node == null) return;
        if (node.getType() == NodeType.PLANNER) {
            throw new IllegalStateException(
                    "PLANNER must be executed via executePlannerOnly when using per-node activities. nodeId=" + node.getId());
        }
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
            log.warn("Node execution failed: nodeId={} type={} error={}", node.getId(), node.getType() != null ? node.getType().getTypeName() : null, t.getMessage(), t);
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
            if (log.isInfoEnabled()) {
                log.info("Tree runOneNodeInTree done | nodeId={} type={}", node.getId(), node.getType());
            }
        }
    }

    private void runSubtree(RuntimeExecutionTree tree, String fromNodeId, PipelineDefinition pipeline,
                           VariableEngine variableEngine, String queueName) {
        if (tree == null || fromNodeId == null) return;
        while (true) {
            String nextId = tree.findNextExecutable();
            if (nextId == null) break;
            if (!tree.isDescendant(nextId, fromNodeId)) break;
            ExecutionTreeNode n = tree.getDefinition(nextId);
            if (n == null) {
                tree.markCompleted(nextId);
                continue;
            }
            runOneNodeInTree(n, pipeline, variableEngine, queueName, tree);
            tree.markCompleted(nextId);
        }
    }

    private void runOneNodeInTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName, RuntimeExecutionTree runtimeTree) {
        Objects.requireNonNull(runtimeTree, "runtimeTree must not be null so planner expansion is visible to findNextExecutable");
        if (log.isInfoEnabled()) {
            log.info("Tree runOneNodeInTree entry | nodeId={} type={} displayName={}", node.getId(), node.getType(), node.getDisplayName());
        }
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
        boolean isPlugin = node.getType() == NodeType.PLUGIN;
        String pluginId = isPlugin && node.getPluginRef() != null ? node.getPluginRef() : null;
        NodeExecutionContext context = new NodeExecutionContext(
                node.getId(), node.getType().getTypeName(), node.getNodeType(), null, tenantId, tenantConfigMap,
                queueName, pluginId, null);
        boolean isActivity = NodeActivityPredicate.isActivityNode(node);
        if (isActivity) {
            featureRunner.runPre(resolved, context, registry);
        }
        Object nodeResult = null;
        boolean executionSucceeded = false;
        try {
            nodeResult = dispatcher.dispatchWithTree(node, pipeline, variableEngine, queueName, runtimeTree,
                    (fromNodeId) -> runSubtree(runtimeTree, fromNodeId, pipeline, variableEngine, queueName));
            executionSucceeded = true;
            if (isActivity) {
                featureRunner.runPostSuccess(resolved, context.withExecutionSucceeded(true), nodeResult, registry);
            }
        } catch (Throwable t) {
            runtimeTree.markFailed(node.getId());
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
