package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Single responsibility: handle PLUGIN nodes (invoke plugin with current variable engine).
 */
public final class PluginHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(PluginHandler.class);

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.PLUGIN);
    }

    @Override
    public Object dispatch(ExecutionTreeNode node,
                           PipelineDefinition pipeline,
                           VariableEngine variableEngine,
                           String queueName,
                           ChildNodeRunner runChild,
                           ChildNodeRunner runChildSync,
                           HandlerContext ctx) {
        if (log.isInfoEnabled()) {
            log.info("Invoking PLUGIN | nodeId={} | pluginRef={} | displayName={}",
                    node.getId(), node.getPluginRef(), node.getDisplayName());
        }
        return ctx.getPluginInvoker().invoke(node, variableEngine);
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
        return dispatch(node, pipeline, variableEngine, queueName,
                (n, p, v, q) -> {}, (n, p, v, q) -> {}, ctx);
    }
}

