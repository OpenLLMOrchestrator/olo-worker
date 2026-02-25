package com.olo.plugin.image.invokeai;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the InvokeAI image plugin. Reads INVOKEAI_BASE_URL from env.
 * Only registers when INVOKEAI_BASE_URL is set.
 */
public final class InvokeAIPluginProvider implements PluginProvider {

    private final InvokeAIImagePlugin plugin;

    public InvokeAIPluginProvider() {
        String baseUrl = System.getenv("INVOKEAI_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:9090";
        this.plugin = new InvokeAIImagePlugin(baseUrl, null);
    }

    @Override
    public boolean isEnabled() {
        String u = System.getenv("INVOKEAI_BASE_URL");
        return u != null && !u.isBlank();
    }

    @Override
    public String getPluginId() {
        return "INVOKEAI";
    }

    @Override
    public String getContractType() {
        return ContractType.IMAGE_GENERATOR;
    }

    @Override
    public ExecutablePlugin getPlugin() {
        return plugin;
    }
}
