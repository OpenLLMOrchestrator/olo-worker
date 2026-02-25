package com.olo.plugin;

import com.olo.config.TenantConfig;

import java.util.Map;

/**
 * Contract for a model-executor plugin: accepts named inputs (e.g. "prompt") and returns
 * named outputs (e.g. "responseText"). Aligns with scope plugin definition
 * {@code contractType: "MODEL_EXECUTOR"} and execution tree input/output mappings.
 * <p>
 * Use {@link #execute(Map, TenantConfig)} to receive tenant-specific configuration
 * (e.g. 3rd party base URL, restrictions). The no-arg overload uses {@link TenantConfig#EMPTY}.
 */
public interface ModelExecutorPlugin extends ExecutablePlugin {

    /**
     * Executes the model with the given inputs. Default implementation delegates to
     * {@link #execute(Map, TenantConfig)} with {@link TenantConfig#EMPTY}.
     *
     * @param inputs map of parameter names to values (e.g. "prompt" → user message)
     * @return map of output parameter names to values (e.g. "responseText" → model response)
     * @throws Exception on execution or model failure
     */
    default Map<String, Object> execute(Map<String, Object> inputs) throws Exception {
        return execute(inputs, TenantConfig.EMPTY);
    }

    /**
     * Executes the model with the given inputs and tenant-specific configuration.
     *
     * @param inputs      map of parameter names to values (e.g. "prompt" → user message)
     * @param tenantConfig tenant config (e.g. "ollamaBaseUrl", "restrictions"); never null
     * @return map of output parameter names to values (e.g. "responseText" → model response)
     * @throws Exception on execution or model failure
     */
    Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception;
}
