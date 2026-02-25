package com.olo.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.TenantConfigRegistry;

import java.util.Map;
import java.util.Objects;

/**
 * Implementation of the protocol {@link com.olo.plugin.PluginExecutor} that resolves
 * plugins from {@link PluginRegistry} and invokes them. Used so the worker depends only
 * on the contract; this class lives in the plugin module and is wired in by bootstrap.
 */
public final class RegistryPluginExecutor implements com.olo.plugin.PluginExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String tenantId;
    @SuppressWarnings("unchecked")
    private final Map<String, ExecutablePlugin> nodeInstanceCache;

    public RegistryPluginExecutor(String tenantId, Map<String, ?> nodeInstanceCache) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.nodeInstanceCache = nodeInstanceCache instanceof Map ? (Map<String, ExecutablePlugin>) nodeInstanceCache : null;
    }

    @Override
    public String execute(String pluginId, String inputsJson, String nodeId) {
        ExecutablePlugin plugin = (nodeId != null && nodeInstanceCache != null)
                ? PluginRegistry.getInstance().getExecutable(tenantId, pluginId, nodeId, nodeInstanceCache)
                : PluginRegistry.getInstance().getExecutable(tenantId, pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("No plugin registered for tenant=" + tenantId + " id=" + pluginId);
        }
        Map<String, Object> inputs;
        try {
            inputs = MAPPER.readValue(inputsJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid inputs JSON for plugin " + pluginId + ": " + e.getMessage(), e);
        }
        var tenantConfig = TenantConfigRegistry.getInstance().get(tenantId);
        try {
            Map<String, Object> outputs = plugin.execute(inputs != null ? inputs : Map.of(), tenantConfig);
            return MAPPER.writeValueAsString(outputs != null ? outputs : Map.of());
        } catch (Exception e) {
            throw new RuntimeException("Plugin execution failed: " + pluginId + " - " + e.getMessage(), e);
        }
    }

    @Override
    public String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map != null ? map : Map.of());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize plugin inputs", e);
        }
    }

    @Override
    public Map<String, Object> fromJson(String json) {
        try {
            return MAPPER.readValue(json != null ? json : "{}", MAP_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize plugin outputs", e);
        }
    }
}
