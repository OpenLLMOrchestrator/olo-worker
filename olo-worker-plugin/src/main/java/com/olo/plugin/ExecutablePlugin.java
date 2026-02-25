package com.olo.plugin;

import com.olo.config.TenantConfig;

import java.util.Map;

/**
 * Base contract for all plugins: execute with input map and tenant config, return output map.
 * All contract types (model executor, embedding, vector store, image generation) extend this so
 * the worker can invoke any plugin without depending on concrete types.
 * <p>
 * <b>Threading and state:</b> Plugin instances are run-scoped (one per node; same node in a loop
 * reuses the same instance). The engine invokes nodes sequentially per run. Do not assume
 * cross-node instance sharing. Implement as thread-safe if using mutable state, so behavior remains
 * correct if the engine introduces parallel node execution later. See docs: Threading and State Model.
 */
public interface ExecutablePlugin {

    /**
     * Executes the plugin with the given inputs and tenant configuration.
     *
     * @param inputs       map of parameter names to values (contract-specific)
     * @param tenantConfig tenant config; never null
     * @return map of output parameter names to values (contract-specific)
     * @throws Exception on execution failure
     */
    Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception;
}
