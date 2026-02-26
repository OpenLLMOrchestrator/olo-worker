package com.olo.bootstrap;

import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricherFactory;
import com.olo.plugin.PluginExecutorFactory;

/**
 * Extended bootstrap context returned from {@code OloBootstrap.initializeWorker()}.
 * Adds run ledger, session cache, plugin executor factory, and dynamic-node builder/enricher
 * so the worker and planner depend only on protocol contracts.
 * <p>
 * Types are {@link Object} where needed so that protocol does not depend on ledger or
 * configuration implementation.
 */
public interface WorkerBootstrapContext extends BootstrapContext {

    /** Run ledger (or null if disabled). Concrete type: {@link com.olo.ledger.RunLedger}. */
    Object getRunLedger();

    /** Session cache. Concrete type: {@link com.olo.config.OloSessionCache}. */
    Object getSessionCache();

    /**
     * Factory that creates {@link com.olo.plugin.PluginExecutor} for a tenant.
     * Implementation (e.g. from plugin module) uses PluginRegistry; worker uses only this contract.
     */
    PluginExecutorFactory getPluginExecutorFactory();

    /**
     * Builder for fully designed dynamic nodes (planner requests new nodes through this contract).
     * Implementation in bootstrap; returns nodes with pipeline/queue features attached.
     */
    DynamicNodeBuilder getDynamicNodeBuilder();

    /**
     * Factory for {@link com.olo.node.NodeFeatureEnricher} (attach features to existing nodes).
     * Used when enriching dynamic steps from JSON in the worker.
     */
    NodeFeatureEnricherFactory getNodeFeatureEnricherFactory();

    /**
     * Invokes resource cleanup (e.g. {@link com.olo.annotations.ResourceCleanup#onExit()}) on all
     * registered plugins and features. Implementation in bootstrap; worker calls this on shutdown
     * without depending on PluginRegistry or FeatureRegistry.
     */
    void runResourceCleanup();
}
