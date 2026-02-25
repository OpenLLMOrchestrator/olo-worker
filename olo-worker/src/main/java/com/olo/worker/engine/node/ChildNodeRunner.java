package com.olo.worker.engine.node;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.worker.engine.VariableEngine;

/**
 * Callback to run a child node (used by NodeExecutionDispatcher for SEQUENCE, IF, FORK, etc.).
 */
@FunctionalInterface
public interface ChildNodeRunner {

    void run(ExecutionTreeNode child, PipelineDefinition pipeline,
             VariableEngine variableEngine, String queueName);
}
