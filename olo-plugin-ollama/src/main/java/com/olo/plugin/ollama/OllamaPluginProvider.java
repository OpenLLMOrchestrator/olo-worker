package com.olo.plugin.ollama;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the Ollama model-executor plugin. Reads OLLAMA_BASE_URL and OLLAMA_MODEL from env.
 */
public final class OllamaPluginProvider implements PluginProvider {

    private final OllamaModelExecutorPlugin plugin;

    public OllamaPluginProvider() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        String model = System.getenv("OLLAMA_MODEL");
        if (model == null || model.isBlank()) model = "llama3.2";
        this.plugin = new OllamaModelExecutorPlugin(baseUrl, model);
    }

    @Override
    public String getPluginId() {
        return "GPT4_EXECUTOR";
    }

    @Override
    public String getContractType() {
        return ContractType.MODEL_EXECUTOR;
    }

    @Override
    public ExecutablePlugin getPlugin() {
        return plugin;
    }
}
