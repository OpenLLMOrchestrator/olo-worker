package com.olo.plugin.litellm;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the LiteLLM (OpenAI-compatible) model executor. Reads LITELLM_BASE_URL and LITELLM_MODEL from env.
 * Always registered so the second model in FORK pipelines is available; defaults to http://localhost:4000 when LITELLM_BASE_URL is not set.
 */
public final class LiteLLMPluginProvider implements PluginProvider {

    private final LiteLLMModelExecutorPlugin plugin;

    public LiteLLMPluginProvider() {
        String baseUrl = System.getenv("LITELLM_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:4000";
        String model = System.getenv("LITELLM_MODEL");
        if (model == null || model.isBlank()) model = "ollama/llama3.2";
        this.plugin = new LiteLLMModelExecutorPlugin(baseUrl, model);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getPluginId() {
        return "LITELLM_EXECUTOR";
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
