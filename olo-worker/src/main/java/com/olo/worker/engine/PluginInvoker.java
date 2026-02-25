package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.features.PluginExecutionResult;
import com.olo.plugin.PluginExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single responsibility: invoke a plugin node (inputMappings → plugin → outputMappings).
 * Reads variables from the engine, calls the plugin, writes outputs back.
 * Measures duration at the exact execution boundary and returns {@link PluginExecutionResult}
 * so features get duration and success without recomputation (and so retry/wrapping logic
 * does not skew timing). Uses the protocol {@link PluginExecutor} so the worker depends on contract only.
 */
public final class PluginInvoker {

    private final PluginExecutor pluginExecutor;

    public PluginInvoker(PluginExecutor pluginExecutor) {
        this.pluginExecutor = pluginExecutor;
    }

    /**
     * Executes the PLUGIN node: builds inputs, calls plugin (timing inside this method), applies outputMappings.
     *
     * @param node          PLUGIN node (pluginRef, inputMappings, outputMappings)
     * @param variableEngine variable map (read inputs, write outputs)
     * @return {@link PluginExecutionResult} with outputs, durationMs and success; null if pluginRef blank
     */
    public Object invoke(ExecutionTreeNode node, VariableEngine variableEngine) {
        String pluginRef = node.getPluginRef();
        if (pluginRef == null || pluginRef.isBlank()) {
            return null;
        }
        Map<String, Object> pluginInputs = new LinkedHashMap<>();
        for (ParameterMapping m : node.getInputMappings()) {
            Object val = variableEngine.get(m.getVariable());
            pluginInputs.put(m.getPluginParameter(), val != null ? val : "");
        }
        String inputsJson = pluginExecutor.toJson(pluginInputs);
        long start = System.currentTimeMillis();
        String outputsJson = pluginExecutor.execute(pluginRef, inputsJson, node.getId());
        long durationMs = System.currentTimeMillis() - start;
        Map<String, Object> outputs = pluginExecutor.fromJson(outputsJson);
        for (ParameterMapping m : node.getOutputMappings()) {
            Object val = outputs != null ? outputs.get(m.getPluginParameter()) : null;
            variableEngine.put(m.getVariable(), val != null ? val : "");
        }
        return new PluginExecutionResult(outputs != null ? outputs : Map.of(), durationMs, true);
    }

    /**
     * Invokes a plugin with variable-to-parameter mappings (for LLM_DECISION, EVALUATION, REFLECTION).
     *
     * @param pluginRef        plugin id
     * @param inputVarToParam  variable name -> plugin input parameter name
     * @param outputParamToVar plugin output parameter name -> variable name
     * @param variableEngine  read inputs from and write outputs to
     * @return value written to first output variable, or null
     */
    public Object invokeWithVariableMapping(String pluginRef,
                                             Map<String, String> inputVarToParam,
                                             Map<String, String> outputParamToVar,
                                             VariableEngine variableEngine) {
        if (pluginRef == null || pluginRef.isBlank()) return null;
        Map<String, Object> pluginInputs = new LinkedHashMap<>();
        if (inputVarToParam != null) {
            for (Map.Entry<String, String> e : inputVarToParam.entrySet()) {
                Object val = variableEngine.get(e.getKey());
                pluginInputs.put(e.getValue(), val != null ? val : "");
            }
        }
        String inputsJson = pluginExecutor.toJson(pluginInputs);
        String outputsJson = pluginExecutor.execute(pluginRef, inputsJson, null);
        Map<String, Object> outputs = pluginExecutor.fromJson(outputsJson);
        Object firstOutput = null;
        if (outputParamToVar != null) {
            for (Map.Entry<String, String> e : outputParamToVar.entrySet()) {
                Object val = outputs != null ? outputs.get(e.getKey()) : null;
                variableEngine.put(e.getValue(), val != null ? val : "");
                if (firstOutput == null) firstOutput = val;
            }
        }
        return firstOutput;
    }

    /**
     * Invokes a plugin with a raw input map and returns the full output map.
     * Used when the caller needs the complete plugin response (e.g. SUBTREE_CREATOR returns variablesToInject + steps).
     */
    public Map<String, Object> invokeWithInputMap(String pluginRef, Map<String, Object> inputMap) {
        if (pluginRef == null || pluginRef.isBlank()) return Map.of();
        String inputsJson = pluginExecutor.toJson(inputMap != null ? inputMap : Map.of());
        String outputsJson = pluginExecutor.execute(pluginRef, inputsJson, null);
        Map<String, Object> outputs = pluginExecutor.fromJson(outputsJson);
        return outputs != null ? outputs : Map.of();
    }

}
