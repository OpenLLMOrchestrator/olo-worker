package com.olo.tool.research;

import com.olo.plugin.ContractType;
import com.olo.tools.ToolCategory;
import com.olo.tools.ToolProvider;

/**
 * Provider for RESEARCH_TOOL. Used in execution tree as pluginRef "RESEARCH_TOOL".
 * Reads OLLAMA_BASE_URL and OLLAMA_MODEL from env (same as Ollama plugin). In container set
 * OLLAMA_BASE_URL=http://ollama:11434 so the worker can reach the Ollama service.
 */
public final class ResearchToolProvider implements ToolProvider {

    public static final String PLUGIN_ID = "RESEARCH_TOOL";

    private final ResearchTool plugin = createFromEnv();

    private static ResearchTool createFromEnv() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        String model = System.getenv("OLLAMA_MODEL");
        if (model == null || model.isBlank()) model = "llama3.2";
        return new ResearchTool(baseUrl, model);
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
