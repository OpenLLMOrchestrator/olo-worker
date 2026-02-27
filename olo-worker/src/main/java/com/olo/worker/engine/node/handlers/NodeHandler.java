package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;

import java.util.function.Consumer;

/**
 * Single responsibility: handle execution for one or more node types (dispatch or tree-driven).
 */
public interface NodeHandler {

    /**
     * Handle in recursive dispatch mode (runChild/runChildSync). Return null for "no result".
     */
    default Object dispatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                            VariableEngine variableEngine, String queueName,
                            ChildNodeRunner runChild, ChildNodeRunner runChildSync,
                            HandlerContext ctx) {
        return null;
    }

    /**
     * Handle in tree-driven mode (tree mutation, subtreeRunner). Return null for "no result".
     */
    default Object dispatchWithTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                    VariableEngine variableEngine, String queueName,
                                    com.olo.worker.engine.runtime.RuntimeExecutionTree tree,
                                    Consumer<String> subtreeRunner,
                                    com.olo.worker.engine.node.ExpansionState expansionState,
                                    com.olo.worker.engine.node.ExpansionLimits expansionLimits,
                                    HandlerContext ctx) {
        return null;
    }
}
