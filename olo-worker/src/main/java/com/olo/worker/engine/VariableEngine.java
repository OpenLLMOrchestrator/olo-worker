package com.olo.worker.engine;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.inputcontract.InputContract;
import com.olo.executiontree.variableregistry.VariableRegistryEntry;
import com.olo.executiontree.variableregistry.VariableScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Single responsibility: variable map lifecycle for execution.
 * Initializes IN from input, INTERNAL and OUT to null; rejects unknown input names when strict.
 * Uses ConcurrentHashMap so ASYNC execution (multiple threads) is safe.
 * Null values are stored as a sentinel because ConcurrentHashMap does not allow null values.
 */
public final class VariableEngine {

    /** Sentinel for null values; ConcurrentHashMap does not allow null. */
    private static final Object NULL = new Object();

    private final Map<String, Object> variableMap;

    /**
     * Builds the variable map from the pipeline registry and input values.
     * IN variables get value from inputValues or null; INTERNAL and OUT start null.
     * When inputContract.strict is true, keys in inputValues not in the input contract are rejected.
     */
    public VariableEngine(PipelineDefinition pipeline, Map<String, Object> inputValues) {
        Objects.requireNonNull(pipeline, "pipeline");
        Map<String, Object> inputValuesCopy = inputValues != null ? new ConcurrentHashMap<>(inputValues) : new ConcurrentHashMap<>();
        InputContract inputContract = pipeline.getInputContract();
        if (inputContract != null && inputContract.isStrict()) {
            Set<String> allowed = pipeline.getVariableRegistry().stream()
                    .filter(e -> e.getScope() == VariableScope.IN)
                    .map(VariableRegistryEntry::getName)
                    .collect(Collectors.toSet());
            for (String key : inputValuesCopy.keySet()) {
                if (key != null && !allowed.contains(key)) {
                    throw new IllegalArgumentException("Strict mode: unknown input parameter: " + key);
                }
            }
        }
        this.variableMap = new ConcurrentHashMap<>();
        List<VariableRegistryEntry> registry = pipeline.getVariableRegistry();
        if (registry != null) {
            for (VariableRegistryEntry entry : registry) {
                String name = entry.getName();
                if (name == null) continue;
                if (entry.getScope() == VariableScope.IN) {
                    Object val = inputValuesCopy.get(name);
                    variableMap.put(name, val != null ? val : NULL);
                } else {
                    variableMap.put(name, NULL);
                }
            }
        }
    }

    /**
     * Builds a variable engine from an existing variable map (e.g. from a previous execution step).
     * Copies existing keys and ensures all pipeline registry variables exist (with null if missing)
     * so that getExportMap() returns a consistent set of keys and merging parallel activity results
     * does not drop variables that one branch did not write.
     */
    public static VariableEngine fromVariableMap(PipelineDefinition pipeline, Map<String, Object> existingVariableMap) {
        Objects.requireNonNull(pipeline, "pipeline");
        ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
        List<VariableRegistryEntry> registry = pipeline.getVariableRegistry();
        if (registry != null) {
            for (VariableRegistryEntry entry : registry) {
                String name = entry.getName();
                if (name == null) continue;
                Object v = existingVariableMap != null ? existingVariableMap.get(name) : null;
                map.put(name, v == null ? NULL : v);
            }
        }
        if (existingVariableMap != null) {
            for (Map.Entry<String, Object> e : existingVariableMap.entrySet()) {
                if (e.getKey() != null && !map.containsKey(e.getKey())) {
                    Object v = e.getValue();
                    map.put(e.getKey(), v == null ? NULL : v);
                }
            }
        }
        return new VariableEngine(pipeline, map);
    }

    /** Private constructor for fromVariableMap; backing map already has NULL sentinel. */
    private VariableEngine(PipelineDefinition pipeline, ConcurrentHashMap<String, Object> backingMap) {
        Objects.requireNonNull(pipeline, "pipeline");
        this.variableMap = backingMap != null ? backingMap : new ConcurrentHashMap<>();
    }

    public Map<String, Object> getVariableMap() {
        return variableMap;
    }

    /** Returns a map suitable for JSON serialization (NULL sentinel converted to null). Uses LinkedHashMap so null values are allowed. */
    public Map<String, Object> getExportMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : variableMap.entrySet()) {
            out.put(e.getKey(), e.getValue() == NULL ? null : e.getValue());
        }
        return out;
    }

    public Object get(String name) {
        Object v = variableMap.get(name);
        return v == NULL ? null : v;
    }

    public void put(String name, Object value) {
        variableMap.put(name, value != null ? value : NULL);
    }
}
