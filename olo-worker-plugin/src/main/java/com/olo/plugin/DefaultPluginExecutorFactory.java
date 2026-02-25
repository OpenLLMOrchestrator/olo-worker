package com.olo.plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of the protocol {@link com.olo.plugin.PluginExecutorFactory} that
 * creates {@link RegistryPluginExecutor} instances (which use {@link PluginRegistry}).
 * Bootstrap wires this into {@link com.olo.bootstrap.WorkerBootstrapContext} so the
 * worker receives the factory and does not depend on PluginRegistry directly.
 */
public final class DefaultPluginExecutorFactory implements com.olo.plugin.PluginExecutorFactory {

    @Override
    public com.olo.plugin.PluginExecutor create(String tenantId, Map<String, ?> nodeInstanceCache) {
        Map<String, ?> cache = nodeInstanceCache != null ? nodeInstanceCache : new ConcurrentHashMap<>();
        return new RegistryPluginExecutor(tenantId, cache);
    }
}
