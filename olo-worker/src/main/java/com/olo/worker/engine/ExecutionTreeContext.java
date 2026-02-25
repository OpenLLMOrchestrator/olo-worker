package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable execution context for a single run: holds dynamically attached children (e.g. from PLANNER)
 * and executed state so the executor can run in a while-loop until no unprocessed nodes remain.
 */
public final class ExecutionTreeContext {

    private final Map<String, List<ExecutionTreeNode>> dynamicChildren = new ConcurrentHashMap<>();
    private final Set<String> executedNodeIds = ConcurrentHashMap.newKeySet();

    public void attachDynamicChildren(String parentNodeId, List<ExecutionTreeNode> nodes) {
        if (parentNodeId == null || nodes == null || nodes.isEmpty()) return;
        dynamicChildren.computeIfAbsent(parentNodeId, k -> new ArrayList<>()).addAll(nodes);
    }

    public List<ExecutionTreeNode> getDynamicChildren(String parentNodeId) {
        if (parentNodeId == null) return List.of();
        List<ExecutionTreeNode> list = dynamicChildren.get(parentNodeId);
        return list != null ? List.copyOf(list) : List.of();
    }

    public List<ExecutionTreeNode> getEffectiveChildren(ExecutionTreeNode node) {
        if (node == null) return List.of();
        List<ExecutionTreeNode> staticChildren = node.getChildren();
        if (staticChildren != null && !staticChildren.isEmpty()) {
            return staticChildren;
        }
        return getDynamicChildren(node.getId());
    }

    public void markExecuted(String nodeId) {
        if (nodeId != null) executedNodeIds.add(nodeId);
    }

    public boolean isExecuted(String nodeId) {
        return nodeId != null && executedNodeIds.contains(nodeId);
    }
}
