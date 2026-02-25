package com.olo.bootstrap;

import com.olo.plugin.PluginExecutorFactory;

/**
 * Extended bootstrap context returned from {@code OloBootstrap.initializeWorker()}.
 * Adds run ledger, session cache, and plugin executor factory so the worker can start
 * Temporal without bootstrap logic and without depending on concrete plugin registry.
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
     * Invokes resource cleanup (e.g. {@link com.olo.annotations.ResourceCleanup#onExit()}) on all
     * registered plugins and features. Implementation in bootstrap; worker calls this on shutdown
     * without depending on PluginRegistry or FeatureRegistry.
     */
    void runResourceCleanup();
}
