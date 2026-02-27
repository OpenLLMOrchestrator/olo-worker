package com.olo.worker.activity.node;

import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Single responsibility: helpers for node execution payloads and planner steps.
 */
final class NodeExecutionHelpers {

    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NodeExecutionHelpers() {
    }

    static boolean isFirstNodeInPlan(Map<String, Object> plan, String nodeId) {
        if (nodeId == null) return false;
        @SuppressWarnings("unchecked")
        List<List<Map<String, Object>>> steps = (List<List<Map<String, Object>>>) plan.get("steps");
        if (steps != null && !steps.isEmpty()) {
            List<Map<String, Object>> firstStep = steps.get(0);
            if (firstStep != null && !firstStep.isEmpty()) {
                Object nid = firstStep.get(0).get("nodeId");
                return nodeId.equals(nid != null ? nid.toString() : null);
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> nodes = (List<Map<String, String>>) plan.get("nodes");
        if (nodes != null && !nodes.isEmpty()) {
            String firstNid = nodes.get(0).get("nodeId");
            return nodeId.equals(firstNid);
        }
        return false;
    }

    static Map<String, Object> dynamicStepFromNode(ExecutionTreeNode n) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", n.getId());
        String activityType = NodeType.PLUGIN.name();
        if (n.getPluginRef() != null && !n.getPluginRef().isBlank()) {
            activityType = NodeType.PLUGIN.name() + ":" + n.getPluginRef();
        }
        step.put("activityType", activityType);
        step.put("pluginRef", n.getPluginRef());
        step.put("displayName", n.getDisplayName());
        if (n.getFeatures() != null && !n.getFeatures().isEmpty()) {
            step.put("features", new ArrayList<>(n.getFeatures()));
        }
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
        try {
            steps = MAPPER.readValue(dynamicStepsJson, LIST_MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
        if (steps == null) return null;
        for (Map<String, Object> step : steps) {
            Object id = step.get("nodeId");
            if (id != null && id.toString().equals(nodeId)) {
                return nodeFromDynamicStep(step);
            }
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
        return new ExecutionTreeNode(
                id, displayName, NodeType.PLUGIN, List.of(), "PLUGIN", pluginRef,
                inputMappings, outputMappings,
                features.isEmpty() ? empty : features, empty, empty, empty, empty, empty, empty, empty,
                Map.of(), null, null, null);
    }

    static String buildPluginVersionsJson(PipelineConfiguration config) {
        Map<String, String> versions = new TreeMap<>();
        if (config != null && config.getPipelines() != null) {
            for (PipelineDefinition def : config.getPipelines().values()) {
                if (def == null || def.getExecutionTree() == null) continue;
                collectPluginRefs(def.getExecutionTree(), versions);
            }
        }
        try {
            return MAPPER.writeValueAsString(versions);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void collectPluginRefs(ExecutionTreeNode node, Map<String, String> out) {
        if (node == null) return;
        if (node.getType() == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
            out.putIfAbsent(node.getPluginRef(), "?");
        }
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) {
                collectPluginRefs(child, out);
            }
        }
    }
}

