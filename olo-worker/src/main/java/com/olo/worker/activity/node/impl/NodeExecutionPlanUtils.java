package com.olo.worker.activity.node.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plan-level helpers (first node check, plugin versions JSON). */
final class NodeExecutionPlanUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    static void collectPluginRefs(ExecutionTreeNode node, Map<String, String> out) {
        if (node == null) return;
        if (node.getType() == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
            out.putIfAbsent(node.getPluginRef(), "?");
        }
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) collectPluginRefs(child, out);
        }
    }
}
