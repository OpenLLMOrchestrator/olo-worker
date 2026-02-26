package com.olo.node;

import com.olo.executiontree.tree.ExecutionTreeNode;

/**
 * Abstraction for attaching all applicable pipeline and queue-based features to a newly created
 * or dynamic node. High-level modules (olo-worker, planner, etc.) depend on this interface
 * rather than on the concrete resolution logic.
 * <p>
 * Implementations use pipeline configuration (scope, queue name) to determine which features
 * to attach (e.g. pipeline scope features, debug when queue ends with {@code -debug}) and
 * return a new node with those features merged into its feature list.
 *
 * @see PipelineFeatureContext
 * @see NodeFeatureEnricherFactory
 */
@FunctionalInterface
public interface NodeFeatureEnricher {

    /**
     * Returns a new node with pipeline and queue-based features attached so the node
     * gets the same feature behavior as static nodes (pre/post hooks, debug, metrics, etc.).
     *
     * @param node    the node to enrich (e.g. planner-added or dynamic step); may have empty features
     * @param context pipeline and queue context
     * @return new node with merged features; may return the same instance if nothing to add
     */
    ExecutionTreeNode enrich(ExecutionTreeNode node, PipelineFeatureContext context);
}
