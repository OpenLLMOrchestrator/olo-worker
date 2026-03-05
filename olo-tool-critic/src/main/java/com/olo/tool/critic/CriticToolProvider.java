package com.olo.tool.critic;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for CRITIC_TOOL. Used in execution tree as pluginRef "CRITIC_TOOL".
 * Reads OLLAMA_BASE_URL and OLLAMA_MODEL from env (same as Ollama plugin). In containers,
 * set OLLAMA_BASE_URL=http://ollama:11434 so the worker can reach the Ollama service.
 */
public final class CriticToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "CRITIC_TOOL";

    private final CriticTool plugin = createFromEnv();

    private static CriticTool createFromEnv() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        String model = System.getenv("OLLAMA_MODEL");
        if (model == null || model.isBlank()) model = "llama3.2";
        return new CriticTool(baseUrl, model);
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
