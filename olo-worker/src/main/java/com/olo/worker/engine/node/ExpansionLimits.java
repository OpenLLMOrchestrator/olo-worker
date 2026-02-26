package com.olo.worker.engine.node;

import java.util.Objects;

/**
 * Hard caps for dynamic planner expansion. Enforced in {@link DynamicNodeFactoryImpl#expand}
 * to keep dynamic injection safe. All values must be positive.
 */
public final class ExpansionLimits {

    /** Default: 100 children per expansion, 500 total nodes per run, depth 5, 10 planner invocations per run. */
    public static final ExpansionLimits DEFAULT = new ExpansionLimits(100, 500, 5, 10);

    private final int maxDynamicNodesPerPlanner;
    private final int maxTotalNodesPerRun;
    private final int maxExpansionDepth;
    private final int maxPlannerInvocationsPerRun;

    public ExpansionLimits(int maxDynamicNodesPerPlanner,
                         int maxTotalNodesPerRun,
                         int maxExpansionDepth,
                         int maxPlannerInvocationsPerRun) {
        this.maxDynamicNodesPerPlanner = requirePositive(maxDynamicNodesPerPlanner, "maxDynamicNodesPerPlanner");
        this.maxTotalNodesPerRun = requirePositive(maxTotalNodesPerRun, "maxTotalNodesPerRun");
        this.maxExpansionDepth = requirePositive(maxExpansionDepth, "maxExpansionDepth");
        this.maxPlannerInvocationsPerRun = requirePositive(maxPlannerInvocationsPerRun, "maxPlannerInvocationsPerRun");
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
        return value;
    }

    /** Max children from a single planner expansion. */
    public int getMaxDynamicNodesPerPlanner() {
        return maxDynamicNodesPerPlanner;
    }

    /** Max total nodes in the tree for the run (static + dynamic). */
    public int getMaxTotalNodesPerRun() {
        return maxTotalNodesPerRun;
    }

    /** Max depth at which expansion is allowed (root depth 0; expansion allowed when parent depth &lt; this value). */
    public int getMaxExpansionDepth() {
        return maxExpansionDepth;
    }

    /** Max number of planner expansion invocations per run. */
    public int getMaxPlannerInvocationsPerRun() {
        return maxPlannerInvocationsPerRun;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExpansionLimits that = (ExpansionLimits) o;
        return maxDynamicNodesPerPlanner == that.maxDynamicNodesPerPlanner
                && maxTotalNodesPerRun == that.maxTotalNodesPerRun
                && maxExpansionDepth == that.maxExpansionDepth
                && maxPlannerInvocationsPerRun == that.maxPlannerInvocationsPerRun;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDynamicNodesPerPlanner, maxTotalNodesPerRun, maxExpansionDepth, maxPlannerInvocationsPerRun);
    }
}
