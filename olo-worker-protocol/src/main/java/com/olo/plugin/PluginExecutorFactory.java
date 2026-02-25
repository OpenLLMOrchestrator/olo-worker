package com.olo.plugin;

import java.util.Map;

/**
 * Contract for creating a {@link PluginExecutor} for a given tenant and optional node-instance cache.
 * The worker receives a factory from bootstrap and uses it to create executors; the implementation
 * (in the plugin module) uses {@link com.olo.plugin.PluginRegistry} or similar. This keeps the
 * worker dependent on contract only.
 */
@FunctionalInterface
public interface PluginExecutorFactory {

    /**
     * Creates a plugin executor for the given tenant. The cache may be used to reuse
     * plugin instances per node within a run (same nodeId → same instance).
     *
     * @param tenantId         tenant id (e.g. from workflow context)
     * @param nodeInstanceCache optional; mutable map to cache plugin instances by nodeId; may be null or empty
     * @return executor that resolves and invokes plugins for this tenant
     */
    PluginExecutor create(String tenantId, Map<String, ?> nodeInstanceCache);
}
