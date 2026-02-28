package com.olo.worker.activity.node.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class NodeExecutionStepUtils {

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Map<String, Object> dynamicStepFromNode(ExecutionTreeNode n) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", n.getId());
        String activityType = NodeType.PLUGIN.name();
        if (n.getPluginRef() != null && !n.getPluginRef().isBlank()) activityType = NodeType.PLUGIN.name() + ":" + n.getPluginRef();
        step.put("activityType", activityType);
        step.put("pluginRef", n.getPluginRef());
        step.put("displayName", n.getDisplayName());
        if (n.getFeatures() != null && !n.getFeatures().isEmpty()) step.put("features", new ArrayList<>(n.getFeatures()));
        List<Map<String, String>> inputMappings = new ArrayList<>();
        if (n.getInputMappings() != null) {
            for (ParameterMapping m : n.getInputMappings()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("pluginParameter", m.getPluginParameter());
                entry.put("variable", m.getVariable());
                inputMappings.add(entry);
            }
        }
        step.put("inputMappings", inputMappings);
        List<Map<String, String>> outputMappings = new ArrayList<>();
        if (n.getOutputMappings() != null) {
            for (ParameterMapping m : n.getOutputMappings()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("pluginParameter", m.getPluginParameter());
                entry.put("variable", m.getVariable());
                outputMappings.add(entry);
            }
        }
        step.put("outputMappings", outputMappings);
        return step;
    }

    static ExecutionTreeNode resolveDynamicStep(String nodeId, String dynamicStepsJson) {
        List<Map<String, Object>> steps;
        try { steps = MAPPER.readValue(dynamicStepsJson, LIST_MAP_TYPE); } catch (Exception e) { return null; }
        if (steps == null) return null;
        for (Map<String, Object> step : steps) {
            Object id = step.get("nodeId");
            if (id != null && id.toString().equals(nodeId)) return nodeFromDynamicStep(step);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static ExecutionTreeNode nodeFromDynamicStep(Map<String, Object> step) {
        String id = step.get("nodeId") != null ? step.get("nodeId").toString() : UUID.randomUUID().toString();
        String displayName = step.get("displayName") != null ? step.get("displayName").toString() : "step";
        String pluginRef = step.get("pluginRef") != null ? step.get("pluginRef").toString() : null;
        List<ParameterMapping> inputMappings = new ArrayList<>();
        Object in = step.get("inputMappings");
        if (in instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map) {
                    Map<String, String> m = (Map<String, String>) o;
                    String pp = m != null ? m.get("pluginParameter") : null;
                    String v = m != null ? m.get("variable") : null;
                    if (pp != null && v != null) inputMappings.add(new ParameterMapping(pp, v));
                }
            }
        }
        List<ParameterMapping> outputMappings = new ArrayList<>();
        Object out = step.get("outputMappings");
        if (out instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map) {
                    Map<String, String> m = (Map<String, String>) o;
                    String pp = m != null ? m.get("pluginParameter") : null;
                    String v = m != null ? m.get("variable") : null;
                    if (pp != null && v != null) outputMappings.add(new ParameterMapping(pp, v));
                }
            }
        }
        List<String> features = new ArrayList<>();
        Object feat = step.get("features");
        if (feat instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) features.add(o.toString().trim());
            }
        }
        List<String> empty = List.of();
        return new ExecutionTreeNode(id, displayName, NodeType.PLUGIN, List.of(), "PLUGIN", pluginRef,
                inputMappings, outputMappings, features.isEmpty() ? empty : features, empty, empty, empty, empty, empty, empty, empty,
                Map.of(), null, null, null);
    }
}