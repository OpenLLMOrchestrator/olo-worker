package com.olo.features;

/**
 * Legacy contract for feature logic that runs after a tree node executes (any post phase).
 * Acts as a fallback when a feature does not implement phase-specific contracts
 * ({@link PostSuccessCall}, {@link PostErrorCall}, {@link FinallyCall}, or {@link PreFinallyCall}).
 * For internal (kernel-privileged) features. For observer-only community features, use {@link ObserverPostNodeCall}.
 *
 * @see FeatureRegistry
 * @see PostSuccessCall
 * @see ObserverPostNodeCall
 */
@FunctionalInterface
public interface PostNodeCall {

    /**
     * Called after the node has executed (success, error, or finally phase).
     *
     * @param context    node context (id, type, nodeType, attributes)
     * @param nodeResult result of the node execution (may be null on error)
     */
    void after(NodeExecutionContext context, Object nodeResult);
}
