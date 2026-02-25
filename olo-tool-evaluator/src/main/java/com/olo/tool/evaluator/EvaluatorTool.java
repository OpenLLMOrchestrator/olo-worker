package com.olo.tool.evaluator;

import com.olo.config.TenantConfig;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.ollama.OllamaModelExecutorPlugin;
import com.olo.tools.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Evaluator tool: MODEL_EXECUTOR that delegates to Ollama with an evaluator role.
 * Used in multi-strategy planner (choose best response) and scoring demos.
 */
public final class EvaluatorTool implements Tool, ModelExecutorPlugin {

    private static final String PROMPT_KEY = "prompt";
    private static final String SYSTEM_PREFIX = "You are an evaluator. Compare the given options and choose the best one (or rank them). Respond with a clear choice and brief justification.\n\n";

    private final ModelExecutorPlugin delegate;

    public EvaluatorTool() {
        this.delegate = new OllamaModelExecutorPlugin();
    }

    public EvaluatorTool(String baseUrl, String model) {
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
