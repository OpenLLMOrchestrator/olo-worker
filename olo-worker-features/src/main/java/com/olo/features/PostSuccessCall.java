package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node executes successfully.
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#POST_SUCCESS}.
 * Prefer this (and {@link PostErrorCall}) for <b>heavy lifting</b>: logic that may throw, has significant
 * side effects, or needs to react specifically to success vs error. For lightweight, non–exception-prone
 * code (e.g. logging, metrics), use {@link FinallyCall} or {@link PreFinallyCall#afterFinally} instead.
 */
@FunctionalInterface
public interface PostSuccessCall {

    /**
     * Called after the node has executed successfully.
     *
     * @param context    node context (id, type, nodeType, attributes)
     * @param nodeResult result of the node execution (non-null on success)
     */
    void afterSuccess(NodeExecutionContext context, Object nodeResult);
}
