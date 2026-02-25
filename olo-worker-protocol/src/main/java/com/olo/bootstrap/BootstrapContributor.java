package com.olo.bootstrap;

/**
 * Contract for plugins or features that contribute to bootstrap after the execution tree
 * (pipeline configuration) is attached. Implementations are invoked during worker startup
 * after pipeline config is loaded and (optionally) after plugins are registered, so they can
 * attach metadata (e.g. planner design contract) to the bootstrap context.
 * <p>
 * This interface lives in {@code olo-worker-protocol} so that plugins, tools, and features
 * can implement it without depending on the bootstrap implementation.
 */
@FunctionalInterface
public interface BootstrapContributor {

    /**
     * Called during bootstrap after the execution tree is available. May read from context
     * and write contributor data via {@link BootstrapContext#putContributorData(String, Object)}.
     *
     * @param context bootstrap context (config, pipeline config by queue, tenant ids)
     */
    void contribute(BootstrapContext context);
}

