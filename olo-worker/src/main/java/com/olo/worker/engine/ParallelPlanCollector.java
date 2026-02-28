package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.node.NodeActivityPredicate;

import java.util.ArrayList;
import java.util.List;

/**
 * Single responsibility: collect execution steps for FORK/JOIN/TRY_CATCH and linear sequences.
 */
final class ParallelPlanCollector {

    private ParallelPlanCollector() {
    }

    static ExecutionPlanBuilder.PlanWithParallelResult build(ExecutionTreeNode root) {
        if (root == null) return null;
        List<List<ExecutionPlanBuilder.PlanEntry>> steps = new ArrayList<>();
        int[] tryCatchCatchStepIndex = { -1 };
        String[] tryCatchErrorVariable = { null };
        if (!collectSteps(root, steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return null;
        return steps.isEmpty() ? null
                : new ExecutionPlanBuilder.PlanWithParallelResult(steps,
                tryCatchCatchStepIndex[0] >= 0 ? tryCatchCatchStepIndex[0] : null,
                tryCatchErrorVariable[0]);
    }

    private static boolean collectSteps(ExecutionTreeNode node, List<List<ExecutionPlanBuilder.PlanEntry>> steps,
                                        int[] tryCatchCatchStepIndex, String[] tryCatchErrorVariable) {
        NodeType type = node.getType();
        if (type == null) type = NodeType.UNKNOWN;
        boolean isLeaf = node.getChildren() == null || node.getChildren().isEmpty();
        if (isLeaf) {
            if (!NodeActivityPredicate.isActivityNode(node)) return true;
            String activityType = type.name();
            if (type == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                activityType = type.name() + ":" + node.getPluginRef();
            }
            steps.add(List.of(new ExecutionPlanBuilder.PlanEntry(activityType, node.getId() != null ? node.getId() : "")));
            return true;
        }
        if (type == NodeType.SEQUENCE || type == NodeType.GROUP) {
            for (ExecutionTreeNode child : node.getChildren()) {
                if (!collectSteps(child, steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return false;
            }
            return true;
        }
        if (type == NodeType.FORK) {
            List<ExecutionPlanBuilder.PlanEntry> parallelGroup = new ArrayList<>();
            for (ExecutionTreeNode child : node.getChildren()) {
                if (!NodeActivityPredicate.isActivityNode(child)) return false;
                NodeType childType = child.getType() != null ? child.getType() : NodeType.UNKNOWN;
                String activityType = childType.name();
                if (childType == NodeType.PLUGIN && child.getPluginRef() != null && !child.getPluginRef().isBlank()) {
                    activityType = childType.name() + ":" + child.getPluginRef();
                }
                parallelGroup.add(new ExecutionPlanBuilder.PlanEntry(activityType, child.getId() != null ? child.getId() : ""));
            }
            if (!parallelGroup.isEmpty()) steps.add(parallelGroup);
            return true;
        }
        if (type == NodeType.JOIN) {
            String activityType = type.name();
            if (node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                activityType = type.name() + ":" + node.getPluginRef();
            }
            steps.add(List.of(new ExecutionPlanBuilder.PlanEntry(activityType, node.getId() != null ? node.getId() : "")));
            return true;
        }
        if (type == NodeType.TRY_CATCH) {
            List<ExecutionTreeNode> children = node.getChildren();
            if (children == null || children.size() < 2) return false;
            if (!collectSteps(children.get(0), steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return false;
            int catchIndex = steps.size();
            if (!collectSteps(children.get(1), steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return false;
            tryCatchCatchStepIndex[0] = catchIndex;
            Object ev = node.getParams() != null ? node.getParams().get("errorVariable") : null;
            tryCatchErrorVariable[0] = ev != null ? ev.toString().trim() : null;
            if (tryCatchErrorVariable[0] != null && tryCatchErrorVariable[0].isEmpty()) tryCatchErrorVariable[0] = null;
            return true;
        }
        return false;
    }
}
