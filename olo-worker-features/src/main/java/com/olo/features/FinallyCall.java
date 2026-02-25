package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node completes (success or error).
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#FINALLY}.
 * Prefer this for <b>non–exception-prone</b> code: logging, metrics, lightweight cleanup, or any logic
 * that should run regardless of success/error without throwing. For heavy lifting or logic that may
 * throw and needs success-vs-error handling, use {@link PostSuccessCall} / {@link PostErrorCall}
 * (or {@link PreFinallyCall}) instead.
 */
@FunctionalInterface
public interface FinallyCall {

    /**
     * Called after the node has completed (success or error). Always runs after postSuccess or postError.
     *
     * @param context    node context (id, type, nodeType, attributes)
     * @param nodeResult result of the node execution (may be null if node threw)
     */
    void afterFinally(NodeExecutionContext context, Object nodeResult);
}
