package com.olo.plugin.image.comfyui;

import com.olo.plugin.ContractType;
import com.olo.plugin.ExecutablePlugin;
import com.olo.plugin.PluginProvider;

/**
 * SPI provider for the ComfyUI image plugin. Reads COMFYUI_BASE_URL and COMFYUI_CHECKPOINT from env.
 * Only registers when COMFYUI_BASE_URL is set.
 */
public final class ComfyUIPluginProvider implements PluginProvider {

    private final ComfyUIImagePlugin plugin;

    public ComfyUIPluginProvider() {
        String baseUrl = System.getenv("COMFYUI_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8188";
        String checkpoint = System.getenv("COMFYUI_CHECKPOINT");
        if (checkpoint == null || checkpoint.isBlank()) checkpoint = "v1-5-pruned-emaonly.safetensors";
        this.plugin = new ComfyUIImagePlugin(baseUrl, checkpoint);
    }

    @Override
    public boolean isEnabled() {
        String u = System.getenv("COMFYUI_BASE_URL");
        return u != null && !u.isBlank();
    }

    @Override
    public String getPluginId() {
        return "COMFYUI";
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
