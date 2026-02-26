package com.olo.node;

import com.olo.executiontree.tree.ParameterMapping;

import java.util.List;
import java.util.Map;

/**
 * Semantic description of a child node. The planner provides only this; it does not
 * assign IDs or construct nodes. Worker owns ID policy and node construction.
 * Supports both PLUGIN steps and PLANNER steps (planner-spawns-planner); when
 * {@link #nodeType()} is "PLANNER", {@link #params()} carries modelPluginRef, treeBuilder, etc.
 */
public record NodeSpec(
        String displayName,
        String pluginRef,
        List<ParameterMapping> inputMappings,
        List<ParameterMapping> outputMappings,
        String nodeType,
        Map<String, Object> params
) {
    public NodeSpec {
        inputMappings = inputMappings != null ? List.copyOf(inputMappings) : List.of();
        outputMappings = outputMappings != null ? List.copyOf(outputMappings) : List.of();
        nodeType = (nodeType != null && !nodeType.isBlank()) ? nodeType.trim().toUpperCase() : "PLUGIN";
        params = params != null ? Map.copyOf(params) : Map.of();
    }

    /** Convenience: PLUGIN step (existing behavior). */
    public static NodeSpec plugin(String displayName, String pluginRef,
                                 List<ParameterMapping> inputMappings,
                                 List<ParameterMapping> outputMappings) {
        return new NodeSpec(displayName, pluginRef, inputMappings, outputMappings, "PLUGIN", Map.of());
    }

    /** Convenience: PLANNER step for recursive planning (planner-spawns-planner). */
    public static NodeSpec planner(String displayName, String logicalRef,
                                   Map<String, Object> params) {
        return new NodeSpec(displayName, logicalRef, List.of(), List.of(), "PLANNER", params != null ? params : Map.of());
    }
}
