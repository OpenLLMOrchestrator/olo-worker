package com.olo.worker.engine.node;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single responsibility: run the execution tree loop and delegate each node to a callback.
 */
final class TreeLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(TreeLoopRunner.class);

    interface NodeRunner {
        void runOne(ExecutionTreeNode node,
                    PipelineDefinition pipeline,
                    VariableEngine variableEngine,
                    String queueName,
                    RuntimeExecutionTree runtimeTree,
                    ExpansionState expansionState,
                    ExpansionLimits expansionLimits);
    }

    void run(RuntimeExecutionTree runtimeTree,
             PipelineDefinition pipeline,
             VariableEngine variableEngine,
             String queueName,
             NodeRunner nodeRunner) {
        if (runtimeTree == null || runtimeTree.getRootId() == null) return;
        if (log.isInfoEnabled()) {
            log.info("Tree loop started | rootId={}", runtimeTree.getRootId());
        }
        ExpansionState expansionState = new ExpansionState();
        ExpansionLimits expansionLimits = ExpansionLimits.DEFAULT;
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
                log.info("Tree loop step 3 | iteration={} | nextId={} type={} displayName={} | executing",
                        iteration, nextId, node.getType(), node.getDisplayName());
            }
            if (node == null) {
                if (log.isInfoEnabled()) {
                    log.info("Tree loop step 4 | node definition null for id={} | markCompleted and continue", nextId);
                }
                runtimeTree.markCompleted(nextId);
                continue;
            }
            if (log.isInfoEnabled()) {
                log.info("Tree loop step 5 | runOneNodeInTree | nodeId={} type={}", nextId, node.getType());
            }
            nodeRunner.runOne(node, pipeline, variableEngine, queueName, runtimeTree, expansionState, expansionLimits);
            runtimeTree.markCompleted(nextId);
            if (log.isInfoEnabled()) {
                log.info("Tree loop step 6 | markCompleted | nodeId={} | iteration={}", nextId, iteration);
            }
        }
    }
}

