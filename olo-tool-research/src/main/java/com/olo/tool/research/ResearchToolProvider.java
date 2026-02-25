package com.olo.tool.research;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for RESEARCH_TOOL. Used in execution tree as pluginRef "RESEARCH_TOOL".
 */
public final class ResearchToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "RESEARCH_TOOL";

    private final ResearchTool plugin = new ResearchTool();

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getContractType() {
        return ContractType.MODEL_EXECUTOR;
    }

    @Override
    public ResearchTool getPlugin() {
        return plugin;
    }

    @Override
    public String getDescription() {
        return "Researches a topic and returns structured findings (delegates to model with research-assistant role).";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.RESEARCH;
    }
}
