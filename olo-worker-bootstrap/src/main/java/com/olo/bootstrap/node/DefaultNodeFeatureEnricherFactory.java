package com.olo.bootstrap.node;

import com.olo.node.NodeFeatureEnricher;
import com.olo.node.NodeFeatureEnricherFactory;

/**
 * Bootstrap implementation of {@link NodeFeatureEnricherFactory}. Returns the shared
 * pipeline-based enricher so worker and other callers depend only on the protocol contract.
 */
public final class DefaultNodeFeatureEnricherFactory implements NodeFeatureEnricherFactory {

    private static final DefaultNodeFeatureEnricherFactory INSTANCE = new DefaultNodeFeatureEnricherFactory();

    public static NodeFeatureEnricherFactory getInstance() {
        return INSTANCE;
    }

    private DefaultNodeFeatureEnricherFactory() {
    }

    @Override
    public NodeFeatureEnricher getEnricher() {
        return PipelineNodeFeatureEnricher.getInstance();
    }
}
