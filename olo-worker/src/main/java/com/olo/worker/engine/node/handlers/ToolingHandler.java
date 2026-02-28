package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Single responsibility: EVENT_WAIT, LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION, FILL_TEMPLATE.
 */
public final class ToolingHandler implements NodeHandler {

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(
                NodeType.EVENT_WAIT,
                NodeType.LLM_DECISION,
                NodeType.TOOL_ROUTER,
                NodeType.EVALUATION,
                NodeType.REFLECTION,
                NodeType.FILL_TEMPLATE
        );
    }

    @Override
    public Object dispatch(ExecutionTreeNode node,
                           PipelineDefinition pipeline,
                           VariableEngine variableEngine,
                           String queueName,
                           ChildNodeRunner runChild,
                           ChildNodeRunner runChildSync,
                           HandlerContext ctx) {
        return switch (node.getType()) {
            case EVENT_WAIT -> ToolingExecutions.executeEventWait(node, variableEngine);
            case LLM_DECISION -> ToolingExecutions.executeLlmDecision(node, variableEngine, ctx);
            case TOOL_ROUTER -> ToolingExecutions.executeToolRouter(node, pipeline, variableEngine, queueName, runChild);
            case EVALUATION -> ToolingExecutions.executeEvaluation(node, variableEngine, ctx);
            case REFLECTION -> ToolingExecutions.executeReflection(node, variableEngine, ctx);
            case FILL_TEMPLATE -> ToolingExecutions.executeFillTemplate(node, variableEngine, queueName);
            default -> null;
        };
    }

    @Override
    public Object dispatchWithTree(ExecutionTreeNode node,
                                   PipelineDefinition pipeline,
                                   VariableEngine variableEngine,
                                   String queueName,
                                   RuntimeExecutionTree tree,
                                   Consumer<String> subtreeRunner,
                                   ExpansionState expansionState,
                                   ExpansionLimits expansionLimits,
                                   HandlerContext ctx) {
        // Tree-mode behavior is the same as single-node for these leaf/tool nodes.
        return dispatch(node, pipeline, variableEngine, queueName, (n, p, v, q) -> {}, (n, p, v, q) -> {}, ctx);
    }

}

