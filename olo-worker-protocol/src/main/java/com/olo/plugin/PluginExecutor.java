package com.olo.plugin;

import java.util.Map;

/**
 * Contract for executing a plugin by id with JSON inputs and receiving JSON outputs.
 * The worker (kernel) uses this only; implementations are supplied by the plugin module
 * (e.g. resolving the plugin from {@link com.olo.plugin.PluginRegistry} and invoking it).
 * This allows the worker to depend on contract only and adopt any plugin that follows the protocol.
 */
public interface PluginExecutor {

    /**
     * Executes the plugin with the given inputs.
     *
     * @param pluginId   plugin id (e.g. from tree node pluginRef)
     * @param inputsJson JSON object of input parameter names to values
     * @param nodeId     optional; when non-null, per-node instance is used (same nodeId → same instance in a run)
     * @return JSON object string of output parameter names to values
     */
    String execute(String pluginId, String inputsJson, String nodeId);

    /** Convenience: execute with no node-scoped instance (nodeId = null). */
    default String execute(String pluginId, String inputsJson) {
        return execute(pluginId, inputsJson, null);
    }

    /**
     * Serializes a map to JSON. Used for plugin inputs.
     */
    String toJson(Map<String, Object> map);

    /**
     * Deserializes JSON to a map. Used for plugin outputs.
     */
    Map<String, Object> fromJson(String json);
}
