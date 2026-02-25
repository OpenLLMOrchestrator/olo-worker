package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.node.NodeActivityPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a linear execution plan (list of leaf nodes in execution order) when the tree
 * contains only SEQUENCE, GROUP, and leaf nodes. An activity is any node that has no children
 * (leaf) or is a feature-type node; internal nodes (SEQUENCE, GROUP) are traversed but do not
 * become activity types. Activity type format: "NODETYPE" or "PLUGIN:pluginRef".
 * Returns null if the tree contains any non-linear structure (e.g. IF, SWITCH, FORK).
 */
public final class ExecutionPlanBuilder {

    /**
     * Result of building a plan with parallel and/or try-catch steps.
     */
    public static final class PlanWithParallelResult {
        private final List<List<PlanEntry>> steps;
        private final Integer tryCatchCatchStepIndex;
        private final String tryCatchErrorVariable;

        public PlanWithParallelResult(List<List<PlanEntry>> steps,
                                      Integer tryCatchCatchStepIndex,
                                      String tryCatchErrorVariable) {
            this.steps = steps;
            this.tryCatchCatchStepIndex = tryCatchCatchStepIndex;
            this.tryCatchErrorVariable = tryCatchErrorVariable;
        }

        public List<List<PlanEntry>> getSteps() { return steps; }
        public Integer getTryCatchCatchStepIndex() { return tryCatchCatchStepIndex; }
        public String getTryCatchErrorVariable() { return tryCatchErrorVariable; }
    }

    /**
     * One entry in the execution plan: activity type for Temporal event history and node id.
     */
    public static final class PlanEntry {
        private final String activityType;
        private final String nodeId;

        public PlanEntry(String activityType, String nodeId) {
            this.activityType = Objects.requireNonNull(activityType, "activityType");
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        }

        public String getActivityType() {
            return activityType;
        }

        public String getNodeId() {
            return nodeId;
        }
    }

    /**
     * Flattens the tree to a list of (activityType, nodeId) in execution order.
     * Any node with no children (leaf) is added as an activity; internal nodes (SEQUENCE, GROUP) are traversed only.
     * Activity type format: "NODETYPE" or "PLUGIN:pluginRef" for plugin leaves.
     * Returns null if the tree contains any non-linear structure (e.g. IF, SWITCH, FORK).
     */
    public static List<PlanEntry> buildLinearPlan(ExecutionTreeNode root) {
        if (root == null) return null;
        List<PlanEntry> out = new ArrayList<>();
        if (!flatten(root, out)) return null;
        return out;
    }

    /**
     * Builds an execution plan that can include parallel steps (for FORK) and try-catch steps (for TRY_CATCH).
     * Returns a result with steps and optional tryCatch metadata. Returns null if the tree contains
     * structure we cannot represent (e.g. IF, SWITCH).
     */
    public static PlanWithParallelResult buildPlanWithParallel(ExecutionTreeNode root) {
        if (root == null) return null;
        List<List<PlanEntry>> steps = new ArrayList<>();
        int[] tryCatchCatchStepIndex = { -1 };
        String[] tryCatchErrorVariable = { null };
        if (!collectSteps(root, steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return null;
        return steps.isEmpty() ? null
                : new PlanWithParallelResult(steps, tryCatchCatchStepIndex[0] >= 0 ? tryCatchCatchStepIndex[0] : null,
                        tryCatchErrorVariable[0]);
    }

    /**
     * Collects steps in execution order. FORK adds one step with multiple entries (parallel);
     * TRY_CATCH adds two steps (try, catch) and records catch step index and error variable;
     * SEQUENCE/GROUP recurse; JOIN and activity leaves add one step with one entry.
     */
    private static boolean collectSteps(ExecutionTreeNode node, List<List<PlanEntry>> steps,
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
            steps.add(List.of(new PlanEntry(activityType, node.getId() != null ? node.getId() : "")));
            return true;
        }
        if (type == NodeType.SEQUENCE || type == NodeType.GROUP) {
            for (ExecutionTreeNode child : node.getChildren()) {
                if (!collectSteps(child, steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return false;
            }
            return true;
        }
        if (type == NodeType.FORK) {
            List<PlanEntry> parallelGroup = new ArrayList<>();
            for (ExecutionTreeNode child : node.getChildren()) {
                if (!NodeActivityPredicate.isActivityNode(child)) return false;
                NodeType childType = child.getType() != null ? child.getType() : NodeType.UNKNOWN;
                String activityType = childType.name();
                if (childType == NodeType.PLUGIN && child.getPluginRef() != null && !child.getPluginRef().isBlank()) {
                    activityType = childType.name() + ":" + child.getPluginRef();
                }
                parallelGroup.add(new PlanEntry(activityType, child.getId() != null ? child.getId() : ""));
            }
            if (parallelGroup.isEmpty()) return true;
            steps.add(parallelGroup);
            return true;
        }
        if (type == NodeType.JOIN) {
            String activityType = type.name();
            if (node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                activityType = type.name() + ":" + node.getPluginRef();
            }
            steps.add(List.of(new PlanEntry(activityType, node.getId() != null ? node.getId() : "")));
            return true;
        }
        if (type == NodeType.TRY_CATCH) {
            List<ExecutionTreeNode> children = node.getChildren();
            if (children == null || children.size() < 2) return false;
            ExecutionTreeNode tryChild = children.get(0);
            ExecutionTreeNode catchChild = children.get(1);
            if (!collectSteps(tryChild, steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return false;
            int catchIndex = steps.size();
            if (!collectSteps(catchChild, steps, tryCatchCatchStepIndex, tryCatchErrorVariable)) return false;
            tryCatchCatchStepIndex[0] = catchIndex;
            Object ev = node.getParams() != null ? node.getParams().get("errorVariable") : null;
            tryCatchErrorVariable[0] = ev != null ? ev.toString().trim() : null;
            if (tryCatchErrorVariable[0] != null && tryCatchErrorVariable[0].isEmpty()) tryCatchErrorVariable[0] = null;
            return true;
        }
        return false;
    }

    /**
     * Traverses in execution order; adds only executable activity nodes (leaf + type that does work) to out.
     * Skips empty containers (e.g. SEQUENCE/GROUP with no children).
     * @return true if the subtree is linear (only SEQUENCE, GROUP, and activity leaves), false otherwise
     */
    private static boolean flatten(ExecutionTreeNode node, List<PlanEntry> out) {
        NodeType type = node.getType();
        if (type == null) type = NodeType.UNKNOWN;
        boolean isLeaf = node.getChildren() == null || node.getChildren().isEmpty();
        if (isLeaf) {
            if (!NodeActivityPredicate.isActivityNode(node)) return true; // empty container, skip adding to plan
            String activityType = type.name();
            if (type == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                activityType = type.name() + ":" + node.getPluginRef();
            }
            out.add(new PlanEntry(activityType, node.getId() != null ? node.getId() : ""));
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

    private ExecutionPlanBuilder() {
    }
}
