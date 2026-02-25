package com.olo.plugin.qdrant;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the Qdrant vector store plugin. Reads QDRANT_BASE_URL from env.
 * Only registers when QDRANT_BASE_URL is set.
 */
public final class QdrantPluginProvider implements PluginProvider {

    private final QdrantVectorStorePlugin plugin;

    public QdrantPluginProvider() {
        String baseUrl = System.getenv("QDRANT_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:6333";
        this.plugin = new QdrantVectorStorePlugin(baseUrl);
    }

    @Override
    public boolean isEnabled() {
        String u = System.getenv("QDRANT_BASE_URL");
        return u != null && !u.isBlank();
    }

    @Override
    public String getPluginId() {
        return "QDRANT_VECTOR_STORE";
    }

    @Override
    public String getContractType() {
        return ContractType.VECTOR_STORE;
    }

    @Override
    public ExecutablePlugin getPlugin() {
        return plugin;
    }
}
