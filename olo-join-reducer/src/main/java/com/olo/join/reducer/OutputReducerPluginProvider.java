package com.olo.join.reducer;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * Provider for the join output reducer plugin. Registers as {@code OUTPUT_REDUCER} with contract type REDUCER.
 * Used by JOIN nodes with mergeStrategy REDUCE (and optionally by PLUGIN nodes that need the same formatting).
 */
public final class OutputReducerPluginProvider implements PluginProvider {

    /** Plugin id for scope and execution tree pluginRef (e.g. JOIN node with mergeStrategy REDUCE). */
    public static final String PLUGIN_ID = "OUTPUT_REDUCER";

    private final OutputReducerPlugin plugin = new OutputReducerPlugin();

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getContractType() {
        return ContractType.REDUCER;
    }

    @Override
    public ExecutablePlugin getPlugin() {
        return plugin;
    }
}
