package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node executes successfully.
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#POST_SUCCESS}.
 * Implement this (and optionally other phase contracts) and annotate the class with {@link com.olo.annotations.OloFeature}.
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
