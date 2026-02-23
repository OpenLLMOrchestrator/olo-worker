/**
 * OLO worker plugin contracts and registry. Plugins implement contracts (e.g. {@link com.olo.plugin.ModelExecutorPlugin})
 * and register with {@link com.olo.plugin.PluginRegistry} per tenant by id so the worker can resolve {@code pluginRef}
 * from execution tree nodes and invoke them with input/output mappings.
 * <ul>
 *   <li>{@link com.olo.plugin.ContractType} – contract type constants (MODEL_EXECUTOR, EMBEDDING)</li>
 *   <li>{@link com.olo.plugin.ModelExecutorPlugin} – prompt → responseText contract</li>
 *   <li>{@link com.olo.plugin.PluginRegistry} – tenant-scoped: {@link com.olo.plugin.PluginRegistry#get(String, String) get(tenantId, pluginId)}, {@link com.olo.plugin.PluginRegistry#registerModelExecutor(String, String, ModelExecutorPlugin) registerModelExecutor(tenantId, id, plugin)}</li>
 *   <li>{@link com.olo.annotations.ResourceCleanup} – implement {@link com.olo.annotations.ResourceCleanup#onExit()} to release resources at worker shutdown</li>
 * </ul>
 */
package com.olo.plugin;
