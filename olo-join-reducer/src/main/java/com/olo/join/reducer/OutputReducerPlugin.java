package com.olo.join.reducer;

import com.olo.config.TenantConfig;
import com.olo.plugin.ReducerPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Join reducer plugin that clubs the output of all mapped inputs (e.g. from JOIN branch children)
 * into a single string in the form:
 * <pre>
 * Output From X Model:"xyz"
 * Output From Y Model:"abc"
 * </pre>
 * Used by JOIN nodes with mergeStrategy REDUCE and pluginRef OUTPUT_REDUCER.
 * Inputs: map of source label to value (from inputMappings). Output: combinedOutput.
 */
public final class OutputReducerPlugin implements ReducerPlugin {

    public static final String OUTPUT_KEY = "combinedOutput";

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) {
        if (inputs == null || inputs.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(OUTPUT_KEY, "");
            return out;
        }
        String combined = inputs.entrySet().stream()
                .map(e -> {
                    String label = e.getKey() != null ? e.getKey().toString().trim() : "";
                    if (label.isEmpty()) label = "unknown";
                    Object v = e.getValue();
                    String value = v != null ? v.toString() : "";
                    return "Output From " + label + ":\"" + value + "\"";
                })
                .collect(Collectors.joining("\n"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(OUTPUT_KEY, combined);
        return out;
    }
}
