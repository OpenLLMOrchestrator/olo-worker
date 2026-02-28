package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single responsibility: handle SEQUENCE/GROUP/CASE control-flow containers.
 */
public final class CoreFlowHandler implements NodeHandler {

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.SEQUENCE, NodeType.GROUP, NodeType.CASE);
    }

    @Override
    public Object dispatch(ExecutionTreeNode node,
                           PipelineDefinition pipeline,
                           VariableEngine variableEngine,
                           String queueName,
                           ChildNodeRunner runChild,
                           ChildNodeRunner runChildSync,
                           HandlerContext ctx) {
        NodeType type = node.getType();
        if (type == NodeType.SEQUENCE || type == NodeType.GROUP) {
            for (ExecutionTreeNode child : node.getChildren()) {
                runChild.run(child, pipeline, variableEngine, queueName);
            }
            return null;
        }
        if (type == NodeType.CASE) {
            for (ExecutionTreeNode child : node.getChildren()) {
                runChild.run(child, pipeline, variableEngine, queueName);
            }
        }
        return null;
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
        // Containers are handled by the tree loop; nothing to do here.
        return null;
    }
}

