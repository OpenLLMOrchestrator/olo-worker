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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single responsibility: handle ITERATOR nodes (dispatch + tree mode).
 */
public final class IteratorHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(IteratorHandler.class);

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.ITERATOR);
    }

    @Override
    public Object dispatch(ExecutionTreeNode node,
                           PipelineDefinition pipeline,
                           VariableEngine variableEngine,
                           String queueName,
                           ChildNodeRunner runChild,
                           ChildNodeRunner runChildSync,
                           HandlerContext ctx) {
        String collectionVar = NodeParams.paramString(node, "collectionVariable");
        String itemVar = NodeParams.paramString(node, "itemVariable");
        String indexVar = NodeParams.paramString(node, "indexVariable");
        if (collectionVar == null || itemVar == null) {
            log.warn("ITERATOR node {} missing collectionVariable or itemVariable in params", node.getId());
            return null;
        }
        Object coll = variableEngine.get(collectionVar);
        if (!(coll instanceof Collection)) {
            log.warn("ITERATOR node {} collectionVariable {} is not a Collection", node.getId(), collectionVar);
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        ExecutionTreeNode body = children.get(0);
        int index = 0;
        for (Object item : (Collection<?>) coll) {
            variableEngine.put(itemVar, item);
            if (indexVar != null && !indexVar.isBlank()) {
                variableEngine.put(indexVar, index);
            }
            runChild.run(body, pipeline, variableEngine, queueName);
            index++;
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
        String collectionVar = NodeParams.paramString(node, "collectionVariable");
        String itemVar = NodeParams.paramString(node, "itemVariable");
        String indexVar = NodeParams.paramString(node, "indexVariable");
        if (collectionVar == null || itemVar == null) return null;
        Object coll = variableEngine.get(collectionVar);
        if (!(coll instanceof Collection)) return null;
        Collection<?> collection = (Collection<?>) coll;
        List<String> childIds = tree.getNode(node.getId()) != null ? tree.getNode(node.getId()).getChildIds() : List.of();
        if (childIds.isEmpty()) return null;
        String bodyId = childIds.get(0);
        int index = 0;
        for (Object item : collection) {
            if (indexVar != null) variableEngine.put(indexVar, index);
            variableEngine.put(itemVar, item != null ? item : "");
            if (index > 0) tree.resetSubtreeToNotStarted(bodyId);
            if (subtreeRunner != null) subtreeRunner.accept(bodyId);
            index++;
        }
        return null;
    }
}

