package com.olo.tool.echo;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for ECHO_TOOL. Used in execution tree as pluginRef "ECHO_TOOL".
 * Echoes the input as "ECHO: " + input; works with planner steps (input.prompt).
 */
public final class EchoToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "ECHO_TOOL";

    private final EchoTool plugin = new EchoTool();

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getContractType() {
        return ContractType.MODEL_EXECUTOR;
    }

    @Override
    public EchoTool getPlugin() {
        return plugin;
    }

    @Override
    public String getDescription() {
        return "Echoes the given text as 'ECHO: ' + input. Use for testing or simple passthrough.";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.PLANNER;
    }
}
