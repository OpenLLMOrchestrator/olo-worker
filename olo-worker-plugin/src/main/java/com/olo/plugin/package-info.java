/**
 * OLO worker plugin contracts and registry. Plugins implement contracts (e.g. {@link com.olo.plugin.ModelExecutorPlugin})
 * and register with {@link com.olo.plugin.PluginRegistry} by id so the worker can resolve {@code pluginRef} from
 * execution tree nodes and invoke them with input/output mappings.
 * <ul>
 *   <li>{@link com.olo.plugin.ContractType} – contract type constants (MODEL_EXECUTOR, EMBEDDING)</li>
 *   <li>{@link com.olo.plugin.ModelExecutorPlugin} – prompt → responseText contract</li>
 *   <li>{@link com.olo.plugin.PluginRegistry} – register and look up plugins by id</li>
 * </ul>
 */
package com.olo.plugin;
