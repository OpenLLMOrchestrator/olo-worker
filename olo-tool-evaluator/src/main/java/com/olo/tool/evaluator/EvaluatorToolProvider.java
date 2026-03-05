package com.olo.tool.evaluator;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for EVALUATOR_MODEL. Used in execution tree as pluginRef "EVALUATOR_MODEL".
 * Reads OLLAMA_BASE_URL and OLLAMA_MODEL from env (same as Ollama plugin). In containers,
 * set OLLAMA_BASE_URL=http://ollama:11434 so the worker can reach the Ollama service.
 */
public final class EvaluatorToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "EVALUATOR_MODEL";

    private final EvaluatorTool plugin = createFromEnv();

    private static EvaluatorTool createFromEnv() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        String model = System.getenv("OLLAMA_MODEL");
        if (model == null || model.isBlank()) model = "llama3.2";
        return new EvaluatorTool(baseUrl, model);
    }

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
