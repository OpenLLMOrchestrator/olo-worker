package com.olo.worker.engine.node;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import com.olo.worker.engine.node.handlers.HandlerContext;
import com.olo.worker.engine.node.handlers.NodeHandler;
import com.olo.worker.engine.node.handlers.NodeHandlerRegistry;
import com.olo.worker.engine.node.handlers.CoreFlowHandler;
import com.olo.worker.engine.node.handlers.BranchHandler;
import com.olo.worker.engine.node.handlers.IteratorHandler;
import com.olo.worker.engine.node.handlers.ForkJoinHandler;
import com.olo.worker.engine.node.handlers.ErrorControlHandler;
import com.olo.worker.engine.node.handlers.SubPipelineHandler;
import com.olo.worker.engine.node.handlers.ToolingHandler;
import com.olo.worker.engine.node.handlers.PluginHandler;
import com.olo.worker.engine.node.handlers.PlannerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Single responsibility: dispatch execution by node type (SEQUENCE, IF, PLUGIN, etc.).
 * Uses ChildNodeRunner callbacks to run child nodes; does not run pre/post features.
 */
public final class NodeExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutionDispatcher.class);

    private final HandlerContext handlerContext;
    private final PlannerHandler plannerHandler;
    private final NodeHandlerRegistry handlerRegistry;

    public NodeExecutionDispatcher(PluginInvoker pluginInvoker, PipelineConfiguration config,
                                   ExecutionType executionType, ExecutorService executor,
                                   String ledgerRunId, DynamicNodeBuilder dynamicNodeBuilder,
                                   NodeFeatureEnricher nodeFeatureEnricher) {
        this.handlerContext = new HandlerContext(pluginInvoker, config, executionType, executor, ledgerRunId, dynamicNodeBuilder, nodeFeatureEnricher);
        this.plannerHandler = new PlannerHandler();
        this.handlerRegistry = new NodeHandlerRegistry(List.of(
                new CoreFlowHandler(),
                new BranchHandler(),
                new IteratorHandler(),
                new ForkJoinHandler(),
                new ErrorControlHandler(),
                new SubPipelineHandler(),
                new ToolingHandler(),
                new PluginHandler(),
                plannerHandler
        ));
    }

    /**
     * Execute this node's logic only; returns result (e.g. from PLUGIN). Does not run pre/post features.
     * Use runChild for async child execution, runChildSync for sync (e.g. inside FORK parallel tasks).
     */
    public Object dispatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                           VariableEngine variableEngine, String queueName,
                           ChildNodeRunner runChild, ChildNodeRunner runChildSync) {
        NodeType type = node.getType();
        if (type == null || type == NodeType.UNKNOWN) {
            log.debug("Node {} has null or unknown type; skipping", node.getId());
            return null;
        }
        if (type == NodeType.PLANNER) {
            throw new IllegalStateException(
                    "PLANNER must be executed via tree (NodeExecutor.runWithTree). Single-node/recursive dispatch is not supported. nodeId=" + node.getId());
        }
        NodeHandler handler = handlerRegistry.forType(type);
        return handler.dispatch(node, pipeline, variableEngine, queueName, runChild, runChildSync, handlerContext);
    }

    /**
     * Tree-driven dispatch: state transition only. No special handling for PLANNER.
     * Containers mutate tree (IF/SWITCH skip; PLANNER attachChildren) or no-op; leaves run logic.
     * PLANNER: expansion happens here only — we attach children and return; we do NOT run children inline.
     * The executor loop will pick new nodes via findNextExecutable(). subtreeRunner is for ITERATOR body only.
     */
    public Object dispatchWithTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName, RuntimeExecutionTree tree,
                                  Consumer<String> subtreeRunner, ExpansionState expansionState, ExpansionLimits expansionLimits) {
        if (tree == null) {
            throw new IllegalStateException(
                "Runtime tree is required for tree-driven execution. nodeId=" + node.getId() + " type=" + node.getType()
                    + ". Use NodeExecutor.runWithTree().");
        }
        NodeType type = node.getType();
        if (type == null || type == NodeType.UNKNOWN) return null;
        if (type == NodeType.PLANNER) {
            return plannerHandler.dispatchWithTree(node, pipeline, variableEngine, queueName,
                    tree, subtreeRunner, expansionState, expansionLimits, handlerContext);
        }
        NodeHandler handler = handlerRegistry.forType(type);
        return handler.dispatchWithTree(node, pipeline, variableEngine, queueName,
                tree, subtreeRunner, expansionState, expansionLimits, handlerContext);
    }

    /**
     * Runs planner logic only (model + parse + inject variables). Returns the list of step nodes
     * without attaching to any tree. Used when each step should run as a separate Temporal activity.
     */
    public List<ExecutionTreeNode> runPlannerReturnSteps(ExecutionTreeNode node, PipelineDefinition pipeline,
                                                         VariableEngine variableEngine, String queueName) {
        return plannerHandler.runPlannerReturnSteps(node, pipeline, variableEngine, queueName, handlerContext);
    }
}
