package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node executes with an error (exception).
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#POST_ERROR}.
 * Implement this (and optionally other phase contracts) and annotate the class with {@link com.olo.annotations.OloFeature}.
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
