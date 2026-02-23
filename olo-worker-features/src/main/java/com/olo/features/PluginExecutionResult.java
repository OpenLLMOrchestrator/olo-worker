package com.olo.features;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a PLUGIN node execution: outputs written to the variable engine,
 * duration and success from the exact execution boundary (e.g. inside PluginInvoker).
 * Used by features (e.g. metrics) so timing and success are not recomputed.
 */
public final class PluginExecutionResult {

    private final Map<String, Object> outputs;
    private final long durationMs;
    private final boolean success;

    public PluginExecutionResult(Map<String, Object> outputs, long durationMs, boolean success) {
        this.outputs = outputs != null ? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(outputs)) : Map.of();
        this.durationMs = durationMs;
        this.success = success;
    }

    /** Plugin output map (e.g. responseText, promptTokens, completionTokens, modelId). Unmodifiable. */
    public Map<String, Object> getOutputs() {
        return outputs;
    }

    /** Execution duration in milliseconds (measured inside plugin invoker). */
    public long getDurationMs() {
        return durationMs;
    }

    /** True if the plugin completed without throwing. */
    public boolean isSuccess() {
        return success;
    }
}
