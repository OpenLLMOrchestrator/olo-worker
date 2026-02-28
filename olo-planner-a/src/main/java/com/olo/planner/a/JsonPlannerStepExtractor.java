package com.olo.planner.a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single responsibility: normalize a raw step JsonNode into a JsonPlannerStepInfo
 * (tool id, type, params, input, displayName).
 */
final class JsonPlannerStepExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    JsonPlannerStepInfo extract(JsonNode step, int index) {
        if (step == null || !step.has("toolId")) return null;
        String rawToolId = step.get("toolId").asText(null);
        if (rawToolId == null || rawToolId.isBlank()) return null;
        String toolId = normalizePluginRef(rawToolId);
        String stepType = step.has("type") ? step.get("type").asText("").trim() : "";
        boolean isPlanner = "PLANNER".equalsIgnoreCase(stepType);
        Map<String, Object> params = isPlanner ? paramsFromStep(step) : Map.of();
        JsonNode input = step.has("input") ? step.get("input") : null;
        String displayName = step.has("displayName")
                ? step.get("displayName").asText(toolId)
                : ("step-" + index + "-" + toolId);
        return new JsonPlannerStepInfo(toolId, isPlanner, params, input, displayName);
    }

    /**
     * Builds params map for a PLANNER step. Uses "params" object if present; otherwise
     * copies known planner keys from the step (modelPluginRef, treeBuilder, userQueryVariable, resultVariable).
     */
    private static Map<String, Object> paramsFromStep(JsonNode step) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (step.has("params") && step.get("params").isObject()) {
            Map<String, Object> fromParams = MAPPER.convertValue(step.get("params"), new TypeReference<Map<String, Object>>() {});
            out.putAll(fromParams);
        }
        if (!out.containsKey("modelPluginRef") && step.has("modelPluginRef"))
            out.put("modelPluginRef", step.get("modelPluginRef").asText(null));
        if (!out.containsKey("treeBuilder") && step.has("treeBuilder"))
            out.put("treeBuilder", step.get("treeBuilder").asText(null));
        if (!out.containsKey("userQueryVariable") && step.has("userQueryVariable"))
            out.put("userQueryVariable", step.get("userQueryVariable").asText(null));
        if (!out.containsKey("resultVariable") && step.has("resultVariable"))
            out.put("resultVariable", step.get("resultVariable").asText(null));
        return out;
    }

    private static String normalizePluginRef(String toolId) {
        if (toolId == null || toolId.isBlank()) return toolId;
        return toolId.trim().replace(" ", "_");
    }
}

