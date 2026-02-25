package com.olo.tool.echo;

import com.olo.config.TenantConfig;
import com.olo.plugin.ExecutablePlugin;
import com.olo.tools.Tool;

import java.util.Map;

/**
 * Simple tool that echoes the input as "ECHO: " + input.
 * Accepts "input" or "prompt" (planner steps use "prompt") and returns "responseText" for MODEL_EXECUTOR contract.
 */
public final class EchoTool implements Tool, ExecutablePlugin {

    private static final String KEY_INPUT = "input";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_RESPONSE_TEXT = "responseText";
    private static final String PREFIX = "ECHO: ";

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        Object in = inputs != null && inputs.containsKey(KEY_INPUT) ? inputs.get(KEY_INPUT) : (inputs != null ? inputs.get(KEY_PROMPT) : null);
        String value = in != null ? in.toString().trim() : "";
        String echoed = PREFIX + value;
        return Map.of(KEY_RESPONSE_TEXT, echoed);
    }
}
