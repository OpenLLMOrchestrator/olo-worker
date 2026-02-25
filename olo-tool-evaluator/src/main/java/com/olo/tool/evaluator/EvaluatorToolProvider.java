package com.olo.tool.evaluator;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for EVALUATOR_MODEL. Used in execution tree as pluginRef "EVALUATOR_MODEL".
 */
public final class EvaluatorToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "EVALUATOR_MODEL";

    private final EvaluatorTool plugin = new EvaluatorTool();

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getContractType() {
        return ContractType.MODEL_EXECUTOR;
    }

    @Override
    public EvaluatorTool getPlugin() {
        return plugin;
    }

    @Override
    public String getDescription() {
        return "Evaluates or compares options and selects the best (delegates to model with evaluator role).";
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.EVALUATOR;
    }
}
