package com.olo.node;

/**
 * Factory for obtaining a {@link NodeFeatureEnricher}. High-level modules use this abstraction
 * so they do not depend on the concrete enricher implementation or feature resolution logic.
 * <p>
 * The factory is typically provided at bootstrap; the worker and other consumers get the enricher
 * from the factory when they need to attach pipeline features to newly created or dynamic nodes.
 *
 * @see NodeFeatureEnricher
 * @see PipelineFeatureContext
 */
@FunctionalInterface
public interface NodeFeatureEnricherFactory {

    /**
     * Returns the enricher to use for attaching pipeline and queue-based features to nodes.
     * The same enricher instance may be reused; it is stateless with respect to pipeline context
     * (context is passed per call to {@link NodeFeatureEnricher#enrich}).
     *
     * @return enricher instance; never null
     */
    NodeFeatureEnricher getEnricher();
}
