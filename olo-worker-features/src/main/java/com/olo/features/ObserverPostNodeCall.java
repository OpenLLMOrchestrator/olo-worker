package com.olo.features;

/**
 * Observer contract for feature logic that runs after a tree node executes.
 * For <b>community features only</b>. Implementations must be observer-class: read context and result, log,
 * emit metrics, append attributes. Must not block execution, override failure semantics, or throw to fail the run.
 * If an observer throws, the executor catches and logs; execution continues.
 *
 * @see FeaturePrivilege#COMMUNITY
 * @see PostSuccessCall
 * @see PostErrorCall
 * @see FinallyCall
 * @see PreFinallyCall
 */
@FunctionalInterface
public interface ObserverPostNodeCall {

    /**
     * Called after the node has executed. Observer-only: do not throw to block; do not mutate execution outcome.
     *
     * @param context    node context (read-only)
     * @param nodeResult result of the node execution (may be null)
     */
    void after(NodeExecutionContext context, Object nodeResult);
}
