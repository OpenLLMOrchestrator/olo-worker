package com.olo.worker.engine.node;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable run-scoped state for planner expansion. Tracks how many times
 * a PLANNER node has expanded this run; used to enforce
 * {@link ExpansionLimits#getMaxPlannerInvocationsPerRun()}.
 */
public final class ExpansionState {

    private final AtomicInteger plannerInvocations = new AtomicInteger(0);

    public ExpansionState() {
    }

    /** Number of planner expansions so far this run. */
    public int getPlannerInvocations() {
        return plannerInvocations.get();
    }

    /** Increment after a successful expansion. Returns new count. */
    public int incrementPlannerInvocations() {
        return plannerInvocations.incrementAndGet();
    }
}
