package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;

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

    /** Flattens the tree to (activityType, nodeId) in execution order. Returns null if non-linear. */
    public static List<PlanEntry> buildLinearPlan(ExecutionTreeNode root) {
        if (root == null) return null;
        List<PlanEntry> out = new ArrayList<>();
        if (!LinearPlanFlattener.flatten(root, out)) return null;
        return out;
    }

    /** Builds a plan with parallel steps (FORK) and/or try-catch (TRY_CATCH). Returns null if not representable. */
    public static PlanWithParallelResult buildPlanWithParallel(ExecutionTreeNode root) {
        return ParallelPlanCollector.build(root);
    }

    private ExecutionPlanBuilder() {
    }
}
