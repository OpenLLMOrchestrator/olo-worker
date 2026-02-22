package com.olo.plugin;

import java.util.Map;

/**
 * Contract for a model-executor plugin: accepts named inputs (e.g. "prompt") and returns
 * named outputs (e.g. "responseText"). Aligns with scope plugin definition
 * {@code contractType: "MODEL_EXECUTOR"} and execution tree input/output mappings.
 */
public interface ModelExecutorPlugin {

    /**
     * Executes the model with the given inputs and returns named outputs.
     *
     * @param inputs map of parameter names to values (e.g. "prompt" → user message)
     * @return map of output parameter names to values (e.g. "responseText" → model response)
     * @throws Exception on execution or model failure
     */
    Map<String, Object> execute(Map<String, Object> inputs) throws Exception;
}
