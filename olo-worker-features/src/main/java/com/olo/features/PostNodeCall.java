package com.olo.features;

/**
 * Contract for feature logic that runs after a tree node executes.
 * Implement this (and optionally {@link PreNodeCall}) and annotate the class with {@link com.olo.annotations.OloFeature}.
 */
@FunctionalInterface
public interface PostNodeCall {

    /**
     * Called after the node has executed.
     *
     * @param context   node context (id, type, nodeType, attributes)
     * @param nodeResult result of the node execution (may be null)
     */
    void after(NodeExecutionContext context, Object nodeResult);
}
