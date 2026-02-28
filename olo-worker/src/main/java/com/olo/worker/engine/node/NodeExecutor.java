package com.olo.worker.engine.node;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.ledger.LedgerContext;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Coordinates node execution: delegates to TreeLoopRunner, SingleNodeRunner, AsyncNodeRunner, PlannerOnlyRunner.
 * Single responsibility: wire dependencies and expose runWithTree, executeNode, executeSingleNode, executePlannerOnly.
 */
public final class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    private final ExecutionType executionType;
    private final String ledgerRunId;
    private final SingleNodeRunner singleNodeRunner;
    private final AsyncNodeRunner asyncNodeRunner;
    private final PlannerOnlyRunner plannerOnlyRunner;

    public NodeExecutor(PluginInvoker pluginInvoker, PipelineConfiguration config,
                        ExecutionType executionType, ExecutorService executor,
                        String tenantId, Map<String, Object> tenantConfigMap,
                        String ledgerRunId, DynamicNodeBuilder dynamicNodeBuilder,
                        NodeFeatureEnricher nodeFeatureEnricher) {
        ExecutionType et = executionType != null ? executionType : ExecutionType.SYNC;
        String tid = tenantId != null ? tenantId : "";
        Map<String, Object> tcm = tenantConfigMap != null ? Map.copyOf(tenantConfigMap) : Map.of();
        this.executionType = et;
        this.ledgerRunId = ledgerRunId;
        NodeFeatureRunner featureRunner = new NodeFeatureRunner();
        NodeExecutionDispatcher dispatcher = new NodeExecutionDispatcher(
                pluginInvoker, config, et, executor, ledgerRunId, dynamicNodeBuilder, nodeFeatureEnricher);
        this.singleNodeRunner = new SingleNodeRunner(featureRunner, dispatcher, tid, tcm);
        this.asyncNodeRunner = new AsyncNodeRunner(singleNodeRunner, et, executor, ledgerRunId);
        this.plannerOnlyRunner = new PlannerOnlyRunner(featureRunner, dispatcher, tid, tcm, ledgerRunId);
    }

    /** Constructor without ledger run id (e.g. SUB_PIPELINE or single-pipeline run). */
    public NodeExecutor(PluginInvoker pluginInvoker, PipelineConfiguration config,
                        ExecutionType executionType, ExecutorService executor,
                        String tenantId, Map<String, Object> tenantConfigMap,
                        DynamicNodeBuilder dynamicNodeBuilder, NodeFeatureEnricher nodeFeatureEnricher) {
        this(pluginInvoker, config, executionType, executor, tenantId, tenantConfigMap, null, dynamicNodeBuilder, nodeFeatureEnricher);
    }

    /** Tree-driven execution: findNextExecutable → run one node → markCompleted. PLANNER mutates tree only. */
    public void runWithTree(RuntimeExecutionTree runtimeTree, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName) {
        if (ledgerRunId != null && !ledgerRunId.isBlank()) LedgerContext.setRunId(ledgerRunId);
        try {
            new TreeLoopRunner().run(runtimeTree, pipeline, variableEngine, queueName,
                    this::runOneNodeInTree);
        } finally {
            if (ledgerRunId != null && !ledgerRunId.isBlank()) LedgerContext.clear();
        }
    }

    /** Runs PLANNER only (model + parse + inject variables). Returns step nodes without running them. */
    public List<ExecutionTreeNode> executePlannerOnly(ExecutionTreeNode node, PipelineDefinition pipeline,
                                                      VariableEngine variableEngine, String queueName) {
        return plannerOnlyRunner.executePlannerOnly(node, pipeline, variableEngine, queueName);
    }

    /** Executes a single node only (no recursion). PLANNER must use executePlannerOnly. */
    public void executeSingleNode(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName) {
        if (node == null) return;
        if (node.getType() == NodeType.PLANNER) {
            throw new IllegalStateException(
                    "PLANNER must be executed via executePlannerOnly when using per-node activities. nodeId=" + node.getId());
        }
        if (ledgerRunId != null && !ledgerRunId.isBlank()) LedgerContext.setRunId(ledgerRunId);
        try {
            singleNodeRunner.runOne(node, pipeline, variableEngine, queueName, this::executeNode, this::executeNodeSync);
        } finally {
            if (ledgerRunId != null && !ledgerRunId.isBlank()) LedgerContext.clear();
        }
    }

    /** Execute one node (async or sync); used as runChild by dispatcher. */
    public void executeNode(ExecutionTreeNode node, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName) {
        asyncNodeRunner.executeNode(node, pipeline, variableEngine, queueName, this::executeNode, this::executeNodeSync);
    }

    /** Sync execution of one node; used as runChildSync by dispatcher. */
    public void executeNodeSync(ExecutionTreeNode node, PipelineDefinition pipeline,
                                VariableEngine variableEngine, String queueName) {
        singleNodeRunner.runOne(node, pipeline, variableEngine, queueName, this::executeNode, this::executeNodeSync);
        if (log.isInfoEnabled()) {
            log.info("Tree runOneNodeInTree done | nodeId={} type={}", node.getId(), node.getType());
        }
    }

    private void runSubtree(RuntimeExecutionTree tree, String fromNodeId, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName,
                            ExpansionState expansionState, ExpansionLimits expansionLimits) {
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
            runOneNodeInTree(n, pipeline, variableEngine, queueName, tree, expansionState, expansionLimits);
            tree.markCompleted(nextId);
        }
    }

    private void runOneNodeInTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName, RuntimeExecutionTree runtimeTree,
                                 ExpansionState expansionState, ExpansionLimits expansionLimits) {
        Objects.requireNonNull(runtimeTree, "runtimeTree must not be null so planner expansion is visible to findNextExecutable");
        if (executionType == ExecutionType.ASYNC && node.getType() == NodeType.PLANNER) {
            throw new IllegalStateException("PLANNER not supported with ASYNC (expansion would race). Use SYNC. nodeId=" + node.getId());
        }
        if (log.isInfoEnabled()) {
            log.info("Tree runOneNodeInTree entry | nodeId={} type={} displayName={}", node.getId(), node.getType(), node.getDisplayName());
        }
        singleNodeRunner.runOneInTree(node, pipeline, variableEngine, queueName, runtimeTree,
                (fromNodeId) -> runSubtree(runtimeTree, fromNodeId, pipeline, variableEngine, queueName, expansionState, expansionLimits),
                expansionState, expansionLimits);
    }
}
