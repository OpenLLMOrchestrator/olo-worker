package com.olo.tool.research;

import com.olo.config.TenantConfig;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.ollama.OllamaModelExecutorPlugin;
import com.olo.tools.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Research tool: MODEL_EXECUTOR that delegates to Ollama with a research-assistant system role.
 * Used in multi-strategy planner and research-agent demos.
 */
public final class ResearchTool implements Tool, ModelExecutorPlugin {

    private static final String PROMPT_KEY = "prompt";
    private static final String SYSTEM_PREFIX = "You are a research assistant. Your task is to research the topic and return clear, structured findings. Be concise and factual.\n\n";

    private final ModelExecutorPlugin delegate;

    public ResearchTool() {
        this.delegate = new OllamaModelExecutorPlugin();
    }

    public ResearchTool(String baseUrl, String model) {
        this.delegate = new OllamaModelExecutorPlugin(baseUrl, model);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        Map<String, Object> wrapped = new HashMap<>(inputs != null ? inputs : Map.of());
        Object p = wrapped.get(PROMPT_KEY);
        String prompt = p != null ? p.toString().trim() : "";
        wrapped.put(PROMPT_KEY, SYSTEM_PREFIX + prompt);
        return delegate.execute(wrapped, tenantConfig);
    }
}
