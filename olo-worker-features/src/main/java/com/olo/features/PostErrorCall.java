package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node executes with an error (exception).
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#POST_ERROR}.
 * Prefer this (and {@link PostSuccessCall}) for <b>heavy lifting</b>: logic that may throw, has significant
 * side effects, or needs to react specifically to success vs error. For lightweight, non–exception-prone
 * code (e.g. logging, metrics), use {@link FinallyCall} or {@link PreFinallyCall#afterFinally} instead.
 */
@FunctionalInterface
public interface PostErrorCall {

    /**
     * Called after the node has executed with an error (exception).
     *
     * @param context    node context (id, type, nodeType, attributes)
     * @param nodeResult result of the node execution (often null when error; may hold partial result)
     */
    void afterError(NodeExecutionContext context, Object nodeResult);
}
