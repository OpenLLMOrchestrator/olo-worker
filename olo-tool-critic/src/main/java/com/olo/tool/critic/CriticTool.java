package com.olo.tool.critic;

import com.olo.config.TenantConfig;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.ollama.OllamaModelExecutorPlugin;
import com.olo.tools.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Critic tool: MODEL_EXECUTOR that delegates to Ollama with a critic/reviewer role.
 * Used in reflection-based planner and quality-gating demos.
 */
public final class CriticTool implements Tool, ModelExecutorPlugin {

    private static final String PROMPT_KEY = "prompt";
    private static final String SYSTEM_PREFIX = "You are a critical reviewer. Evaluate the given content for accuracy, clarity, and quality. Provide concise, constructive criticism and suggestions for improvement.\n\n";

    private final ModelExecutorPlugin delegate;

    public CriticTool() {
        this.delegate = new OllamaModelExecutorPlugin();
    }

    public CriticTool(String baseUrl, String model) {
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
