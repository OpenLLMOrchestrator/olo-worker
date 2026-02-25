package com.olo.tool.critic;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for CRITIC_TOOL. Used in execution tree as pluginRef "CRITIC_TOOL".
 */
public final class CriticToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "CRITIC_TOOL";

    private final CriticTool plugin = new CriticTool();

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getContractType() {
        return ContractType.MODEL_EXECUTOR;
    }

    @Override
    public CriticTool getPlugin() {
        return plugin;
    }

    @Override
    public String getDescription() {
        return "Critically reviews content for accuracy and quality (delegates to model with critic role).";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.CRITIC;
    }
}
