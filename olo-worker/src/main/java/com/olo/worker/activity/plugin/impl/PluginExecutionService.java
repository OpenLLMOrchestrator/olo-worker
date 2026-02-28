package com.olo.worker.activity.plugin.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.plugin.PluginExecutor;
import com.olo.plugin.PluginExecutorFactory;

import java.util.Map;

/**
 * Single responsibility: execute plugins and get chat response (prompt → responseText).
 */
public final class PluginExecutionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginExecutorFactory pluginExecutorFactory;

    public PluginExecutionService(PluginExecutorFactory pluginExecutorFactory) {
        this.pluginExecutorFactory = pluginExecutorFactory != null ? pluginExecutorFactory : (tenantId, cache) -> { throw new IllegalStateException("PluginExecutorFactory not set"); };
    }

    public String executePlugin(String tenantId, String pluginId, String inputsJson,
                               String nodeId, Map<String, ?> nodeInstanceCache) {
        PluginExecutor executor = pluginExecutorFactory.create(tenantId, nodeInstanceCache);
        return executor.execute(pluginId, inputsJson, nodeId);
    }

    public String getChatResponse(String pluginId, String prompt) {
        String inputsJson;
        try {
            inputsJson = MAPPER.writeValueAsString(Map.of("prompt", prompt != null ? prompt : ""));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to build prompt input", e);
        }
        String outputsJson = executePlugin(com.olo.config.OloConfig.normalizeTenantId(null), pluginId, inputsJson, null, null);
        try {
            Map<String, Object> outputs = MAPPER.readValue(outputsJson, MAP_TYPE);
            Object text = outputs != null ? outputs.get("responseText") : null;
            return text != null ? text.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
