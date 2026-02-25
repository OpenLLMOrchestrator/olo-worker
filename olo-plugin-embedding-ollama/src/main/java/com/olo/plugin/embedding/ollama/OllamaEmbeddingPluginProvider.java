package com.olo.plugin.embedding.ollama;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the Ollama embedding plugin. Reads OLLAMA_BASE_URL and OLLAMA_EMBEDDING_MODEL from env.
 * Only registers when OLLAMA_EMBEDDING_MODEL is set.
 */
public final class OllamaEmbeddingPluginProvider implements PluginProvider {

    private final OllamaEmbeddingPlugin plugin;

    public OllamaEmbeddingPluginProvider() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
        String model = System.getenv("OLLAMA_EMBEDDING_MODEL");
        if (model == null || model.isBlank()) model = "nomic-embed-text";
        this.plugin = new OllamaEmbeddingPlugin(baseUrl, model);
    }

    @Override
    public boolean isEnabled() {
        String m = System.getenv("OLLAMA_EMBEDDING_MODEL");
        return m != null && !m.isBlank();
    }

    @Override
    public String getPluginId() {
        return "OLLAMA_EMBEDDING";
    }

    @Override
    public String getContractType() {
        return ContractType.EMBEDDING;
    }

    @Override
    public ExecutablePlugin getPlugin() {
        return plugin;
    }
}
