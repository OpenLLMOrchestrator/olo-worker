package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.node.NodeActivityPredicate;

import java.util.List;

/**
 * Single responsibility: flatten a linear tree (SEQUENCE, GROUP, activity leaves) to a list of PlanEntry.
 */
final class LinearPlanFlattener {

    private LinearPlanFlattener() {
    }

    /**
     * Traverses in execution order; adds only activity nodes to out.
     * @return true if the subtree is linear (only SEQUENCE, GROUP, and activity leaves), false otherwise
     */
    static boolean flatten(ExecutionTreeNode node, List<ExecutionPlanBuilder.PlanEntry> out) {
        NodeType type = node.getType();
        if (type == null) type = NodeType.UNKNOWN;
        boolean isLeaf = node.getChildren() == null || node.getChildren().isEmpty();
        if (isLeaf) {
            if (!NodeActivityPredicate.isActivityNode(node)) return true;
            String activityType = type.name();
            if (type == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                activityType = type.name() + ":" + node.getPluginRef();
            }
            out.add(new ExecutionPlanBuilder.PlanEntry(activityType, node.getId() != null ? node.getId() : ""));
            return true;
        }
        if (type == NodeType.SEQUENCE || type == NodeType.GROUP) {
            for (ExecutionTreeNode child : node.getChildren()) {
                if (!flatten(child, out)) return false;
            }
            return true;
        }
        return false;
    }
}
