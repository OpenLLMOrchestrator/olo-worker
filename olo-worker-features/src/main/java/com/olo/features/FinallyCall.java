package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node completes (success or error).
 * Corresponds to phase {@link com.olo.annotations.FeaturePhase#FINALLY}.
 * Implement this (and optionally other phase contracts) and annotate the class with {@link com.olo.annotations.OloFeature}.
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
