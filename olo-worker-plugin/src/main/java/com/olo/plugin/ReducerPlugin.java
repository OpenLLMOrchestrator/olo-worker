package com.olo.plugin;

import com.olo.config.TenantConfig;

import java.util.Map;

/**
 * Contract for a reducer plugin: combines outputs from multiple plugins (e.g. model executors)
 * that ran earlier in the pipeline into a single formatted output.
 * <p>
 * <b>Inputs</b> (via inputMappings): map of <em>source label</em> → value. Each key is a display
 * name for the source (e.g. "X Model", "Y Model"); values are the outputs from previous plugin
 * nodes (mapped from variables those nodes wrote to).
 * <p>
 * <b>Output</b> (via outputMappings): typically one key {@code "combinedOutput"} with a string
 * that clubs all inputs, e.g. {@code "Output From X Model:\"xyz\"\nOutput From Y Model:\"abc\""}.
 * <p>
 * Use a PLUGIN node with {@code pluginRef} pointing to a reducer (e.g. OUTPUT_REDUCER). Map
 * each prior plugin's output variable to a plugin parameter (the label), and map
 * {@code combinedOutput} to a variable for downstream use.
 */
public interface ReducerPlugin extends ExecutablePlugin {

    /**
     * Combines the given labeled outputs into a single result.
     *
     * @param inputs       map of source label → value (e.g. "X Model" → "xyz", "Y Model" → "abc")
     * @param tenantConfig tenant config; never null
     * @return map containing at least "combinedOutput" with the combined string
     * @throws Exception on failure
     */
    @Override
    Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception;
}
