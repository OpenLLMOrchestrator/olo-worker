package com.olo.tools;

import com.olo.plugin.ContractType;
import com.olo.plugin.PluginProvider;

import java.util.Collections;
import java.util.Map;

/**
 * Provider for a tool: extends {@link PluginProvider} and {@link PlannerToolDescriptor}
 * for discovery, demos, and planner use cases. Tools are registered as plugins
 * (same pluginRef in execution tree); this interface adds metadata for tool-specific UIs
 * and for the planner design contract (tooling.availableTools).
 */
public interface ToolProvider extends PluginProvider, PlannerToolDescriptor {

    /**
     * Human-readable description (e.g. "Researches a topic and returns structured findings").
     */
    String getDescription();

    /**
     * Category for grouping (planner, research, critic, evaluator, reducer, etc.).
     */
    ToolCategory getCategory();

    @Override
    Tool getPlugin();

    @Override
    default Tool createPlugin() {
        return getPlugin();
    }

    // --- PlannerToolDescriptor defaults (override in tools for richer schema) ---

    @Override
    default String getToolId() {
        return getPluginId();
    }

    @Override
    default Map<String, String> getInputSchema() {
        String ct = getContractType();
        if (ContractType.MODEL_EXECUTOR.equals(ct)) {
            return Map.of("prompt", "STRING");
        }
        if (ContractType.REDUCER.equals(ct)) {
            return Map.of("labeledInputs", "MAP");
        }
        return Collections.emptyMap();
    }

    @Override
    default Map<String, String> getOutputSchema() {
        String ct = getContractType();
        if (ContractType.MODEL_EXECUTOR.equals(ct)) {
            return Map.of("responseText", "STRING");
        }
        if (ContractType.REDUCER.equals(ct)) {
            return Map.of("combinedOutput", "STRING");
        }
        return Collections.emptyMap();
    }

    @Override
    default Map<String, Object> getCapabilities() {
        return Map.of("supportsParallel", true, "supportsStreaming", false);
    }

    @Override
    default double getEstimatedCost() {
        return 0.0;
    }

    @Override
    default String getVersion() {
        return PluginProvider.super.getVersion();
    }

    @Override
    default String getCategoryName() {
        ToolCategory cat = getCategory();
        return cat != null ? cat.name() : "OTHER";
    }
}
