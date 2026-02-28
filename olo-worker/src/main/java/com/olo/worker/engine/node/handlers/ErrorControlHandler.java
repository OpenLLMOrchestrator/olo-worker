package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.node.NodeParams;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single responsibility: TRY_CATCH and RETRY nodes.
 */
public final class ErrorControlHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorControlHandler.class);

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.TRY_CATCH, NodeType.RETRY);
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
            case TRY_CATCH -> executeTryCatch(node, pipeline, variableEngine, queueName, runChild);
            case RETRY -> executeRetry(node, pipeline, variableEngine, queueName, runChild);
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
        // TRY_CATCH/RETRY are handled in dispatch mode only.
        return null;
    }

    private Object executeTryCatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        try {
            runChild.run(children.get(0), pipeline, variableEngine, queueName);
        } catch (Throwable t) {
            log.warn("TRY_CATCH node {} try-block failed: {} (running catch block or rethrowing)", node.getId(), t.getMessage(), t);
            String errorVar = NodeParams.paramString(node, "errorVariable");
            if (errorVar != null && !errorVar.isBlank()) {
                variableEngine.put(errorVar, t.getMessage() != null ? t.getMessage() : t.toString());
            }
            if (children.size() > 1) {
                runChild.run(children.get(1), pipeline, variableEngine, queueName);
            } else {
                throw t;
            }
        }
        return null;
    }

    private Object executeRetry(ExecutionTreeNode node, PipelineDefinition pipeline,
                                VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            log.warn("RETRY node {} has no child", node.getId());
            return null;
        }
        int maxAttempts = NodeParams.paramInt(node, "maxAttempts", 3);
        long initialMs = NodeParams.paramLong(node, "initialIntervalMs", 0L);
        double backoffCoefficient = NodeParams.paramDouble(node, "backoffCoefficient", 2.0);
        Throwable last = null;
        ExecutionTreeNode child = children.get(0);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                runChild.run(child, pipeline, variableEngine, queueName);
                if (attempt > 1) {
                    log.info("RETRY node {} child {} succeeded on attempt {}/{}", node.getId(), child.getId(), attempt, maxAttempts);
                }
                return null;
            } catch (Throwable t) {
                last = t;
                log.warn("RETRY node {} child {} attempt {}/{} failed: {}",
                        node.getId(), child.getId(), attempt, maxAttempts, t.getMessage(), t);
                if (attempt == maxAttempts) {
                    log.error("RETRY node {} child {} all {} attempts exhausted; failing", node.getId(), child.getId(), maxAttempts);
                    break;
                }
                if (!NodeParams.isRetryable(node, t)) {
                    log.warn("RETRY node {} child {} error not retryable; failing without further attempts", node.getId(), child.getId());
                    throw t;
                }
                long sleepMs = (long) (initialMs * Math.pow(backoffCoefficient, attempt - 1));
                if (sleepMs > 0) {
                    log.info("RETRY node {} child {} backing off {} ms before attempt {}/{}", node.getId(), child.getId(), sleepMs, attempt + 1, maxAttempts);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", e);
                    }
                }
            }
        }
        throw last instanceof RuntimeException ? (RuntimeException) last : new RuntimeException(last);
    }
}

