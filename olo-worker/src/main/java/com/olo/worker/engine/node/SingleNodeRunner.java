package com.olo.worker.engine.node;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.features.FeatureRegistry;
import com.olo.features.NodeExecutionContext;
import com.olo.features.ResolvedPrePost;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Single responsibility: run one node with pre, dispatch, and post (sync or tree mode).
 * Does not manage LedgerContext or async execution; callers do that.
 */
public final class SingleNodeRunner {

    private static final Logger log = LoggerFactory.getLogger(SingleNodeRunner.class);

    private final NodeFeatureRunner featureRunner;
    private final NodeExecutionDispatcher dispatcher;
    private final String tenantId;
    private final Map<String, Object> tenantConfigMap;

    public SingleNodeRunner(NodeFeatureRunner featureRunner, NodeExecutionDispatcher dispatcher,
                            String tenantId, Map<String, Object> tenantConfigMap) {
        this.featureRunner = featureRunner;
        this.dispatcher = dispatcher;
        this.tenantId = tenantId != null ? tenantId : "";
        this.tenantConfigMap = tenantConfigMap != null ? Map.copyOf(tenantConfigMap) : Map.of();
    }

    /**
     * Run one node (pre, dispatch with runChild/runChildSync, post). Used for single-node and recursive execution.
     */
    public void runOne(ExecutionTreeNode node, PipelineDefinition pipeline, VariableEngine variableEngine,
                       String queueName, ChildNodeRunner runChild, ChildNodeRunner runChildSync) {
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
        String pluginId = node.getType() == NodeType.PLUGIN && node.getPluginRef() != null ? node.getPluginRef() : null;
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
            nodeResult = dispatcher.dispatch(node, pipeline, variableEngine, queueName, runChild, runChildSync);
            executionSucceeded = true;
            if (isActivity) {
                featureRunner.runPostSuccess(resolved, context.withExecutionSucceeded(true), nodeResult, registry);
            }
        } catch (Throwable t) {
            log.warn("Node execution failed: nodeId={} type={} error={}",
                    node.getId(), node.getType() != null ? node.getType().getTypeName() : null, t.getMessage(), t);
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

    /**
     * Run one node in tree mode (pre, dispatchWithTree, post). On error marks the node failed on the tree.
     */
    public void runOneInTree(ExecutionTreeNode node, PipelineDefinition pipeline, VariableEngine variableEngine,
                             String queueName, RuntimeExecutionTree runtimeTree, Consumer<String> subtreeRunner,
                             ExpansionState expansionState, ExpansionLimits expansionLimits) {
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureResolver.resolve(node, queueName, pipeline.getScope(), registry);
        String pluginId = node.getType() == NodeType.PLUGIN && node.getPluginRef() != null ? node.getPluginRef() : null;
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
                    subtreeRunner, expansionState, expansionLimits);
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
