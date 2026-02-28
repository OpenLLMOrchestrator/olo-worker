package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.ledger.LedgerContext;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.node.NodeParams;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Single responsibility: handle FORK and JOIN nodes.
 */
public final class ForkJoinHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ForkJoinHandler.class);

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.FORK, NodeType.JOIN);
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
            case FORK -> executeFork(node, pipeline, variableEngine, queueName, runChild, runChildSync, ctx);
            case JOIN -> executeJoin(node, pipeline, variableEngine, queueName, runChild, ctx);
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
        if (node.getType() == NodeType.JOIN) {
            // Tree mode JOIN: run children via no-op ChildNodeRunner (state changes handled by tree).
            return executeJoin(node, pipeline, variableEngine, queueName, (n, p, v, q) -> {}, ctx);
        }
        // FORK is a tree-structure concept; execution is handled by the runtime/executor.
        return null;
    }

    private Object executeFork(ExecutionTreeNode node,
                               PipelineDefinition pipeline,
                               VariableEngine variableEngine,
                               String queueName,
                               ChildNodeRunner runChild,
                               ChildNodeRunner runChildSync,
                               HandlerContext ctx) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        boolean runParallel = ctx.getExecutionType() == ExecutionType.ASYNC
                && ctx.getExecutor() != null
                && children.size() > 1;
        if (runParallel) {
            List<Future<?>> futures = new ArrayList<>(children.size());
            String runId = ctx.getLedgerRunId();
            var executor = ctx.getExecutor();
            for (ExecutionTreeNode child : children) {
                Future<?> future = executor.submit(() -> {
                    if (runId != null && !runId.isBlank()) {
                        LedgerContext.setRunId(runId);
                    }
                    try {
                        runChildSync.run(child, pipeline, variableEngine, queueName);
                    } finally {
                        if (runId != null && !runId.isBlank()) {
                            LedgerContext.clear();
                        }
                    }
                });
                futures.add(future);
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                }
            }
        } else {
            for (ExecutionTreeNode child : children) {
                runChild.run(child, pipeline, variableEngine, queueName);
            }
        }
        return null;
    }

    private Object executeJoin(ExecutionTreeNode node,
                               PipelineDefinition pipeline,
                               VariableEngine variableEngine,
                               String queueName,
                               ChildNodeRunner runChild,
                               HandlerContext ctx) {
        String strategy = NodeParams.paramString(node, "mergeStrategy");
        if (strategy == null || strategy.isBlank()) {
            log.warn("JOIN node {} missing mergeStrategy in params; required (ALL, ANY, FIRST_WINS, LAST_WINS, MAJORITY, REDUCE, PLUGIN). REDUCE/PLUGIN use pluginRef (e.g. OUTPUT_REDUCER) and input/outputMappings.", node.getId());
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        switch (strategy.toUpperCase()) {
            case "ANY":
            case "FIRST_WINS":
                if (!children.isEmpty()) {
                    runChild.run(children.get(0), pipeline, variableEngine, queueName);
                }
                break;
            case "LAST_WINS":
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                break;
            case "PLUGIN":
            case "REDUCE":
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                if (node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                    return ctx.getPluginInvoker().invoke(node, variableEngine);
                }
                break;
            case "ALL":
            case "MAJORITY":
            default:
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                break;
        }
        return null;
    }
}

