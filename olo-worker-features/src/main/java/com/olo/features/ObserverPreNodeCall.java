package com.olo.features;

/**
 * Observer contract for feature logic that runs before a tree node executes.
 * For <b>community features only</b>. Implementations must be observer-class: read context, log, emit metrics,
 * append attributes. Must not block execution, mutate execution plan, or throw to fail the run.
 * If an observer throws, the executor catches and logs; execution continues.
 *
 * @see FeaturePrivilege#COMMUNITY
 * @see PreNodeCall for internal (kernel-privileged) pre hooks that may block
 */
@FunctionalInterface
public interface ObserverPreNodeCall {

    /**
     * Called before the node is executed. Observer-only: do not throw to block; do not mutate execution plan.
     *
     * @param context node context (read-only; do not mutate)
     */
    void before(NodeExecutionContext context);
}
