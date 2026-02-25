package com.olo.plugin.image.sd;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the Stable Diffusion image plugin. Reads STABLE_DIFFUSION_BASE_URL from env.
 * Only registers when STABLE_DIFFUSION_BASE_URL is set.
 */
public final class StableDiffusionPluginProvider implements PluginProvider {

    private final StableDiffusionImagePlugin plugin;

    public StableDiffusionPluginProvider() {
        String baseUrl = System.getenv("STABLE_DIFFUSION_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:7860";
        this.plugin = new StableDiffusionImagePlugin(baseUrl);
    }

    @Override
    public boolean isEnabled() {
        String u = System.getenv("STABLE_DIFFUSION_BASE_URL");
        return u != null && !u.isBlank();
    }

    @Override
    public String getPluginId() {
        return "STABLE_DIFFUSION";
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
