package com.olo.worker.workflow.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single responsibility: merge variable maps and run one plan step (single or parallel).
 */
final class WorkflowVariableMapHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String mergeVariableMaps(String baseVariableMapJson, List<String> resultVariableMapJsonList,
                                    List<List<String>> outputVariablesPerResult) {
        try {
            Map<String, Object> merged = baseVariableMapJson != null && !baseVariableMapJson.isEmpty()
                    ? MAPPER.readValue(baseVariableMapJson, new TypeReference<Map<String, Object>>() {})
                    : new LinkedHashMap<>();
            boolean usePluginOutputsOnly = outputVariablesPerResult != null
                    && outputVariablesPerResult.size() == resultVariableMapJsonList.size();
            for (int i = 0; i < resultVariableMapJsonList.size(); i++) {
                String resultJson = resultVariableMapJsonList.get(i);
                if (resultJson == null || resultJson.isEmpty()) continue;
                Map<String, Object> m = MAPPER.readValue(resultJson, new TypeReference<Map<String, Object>>() {});
                if (m == null) continue;
                if (usePluginOutputsOnly && i < outputVariablesPerResult.size()) {
                    List<String> allowedKeys = outputVariablesPerResult.get(i);
                    if (allowedKeys != null) {
                        for (String key : allowedKeys) {
                            Object val = m.get(key);
                            if (val != null) merged.put(key, val);
                        }
                    }
                } else {
                    for (Map.Entry<String, Object> e : m.entrySet()) {
                        if (e.getValue() != null) merged.put(e.getKey(), e.getValue());
                    }
                }
            }
            return MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            return baseVariableMapJson != null ? baseVariableMapJson : "{}";
        }
    }

    static String mergeErrorIntoVariableMap(String variableMapJson, String errorVariable, String errorMessage) {
        try {
            Map<String, Object> map = variableMapJson != null && !variableMapJson.isEmpty()
                    ? MAPPER.readValue(variableMapJson, new TypeReference<Map<String, Object>>() {})
                    : new LinkedHashMap<>();
            map.put(errorVariable, errorMessage != null ? errorMessage : "");
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return variableMapJson != null ? variableMapJson : "{}";
        }
    }

    static String runStep(ActivityStub untypedActivityStub, String planJson, String queueForActivities,
                          String workflowInputJson, List<Map<String, Object>> step, String variableMapJson) {
        if (step == null || step.isEmpty()) return variableMapJson;
        if (step.size() == 1) {
            Map<String, Object> node = step.get(0);
            String activityType = node.get("activityType") != null ? node.get("activityType").toString() : null;
            String nodeId = node.get("nodeId") != null ? node.get("nodeId").toString() : null;
            if (activityType == null || nodeId == null) return variableMapJson;
            return untypedActivityStub.execute(
                    activityType, String.class,
                    planJson, nodeId, variableMapJson, queueForActivities, workflowInputJson, null);
        }
        List<Promise<String>> promises = new ArrayList<>();
        List<List<String>> outputVariablesPerResult = new ArrayList<>();
        for (Map<String, Object> node : step) {
            String at = node.get("activityType") != null ? node.get("activityType").toString() : null;
            String nid = node.get("nodeId") != null ? node.get("nodeId").toString() : null;
            if (at == null || nid == null) continue;
            List<String> outputVars = new ArrayList<>();
            Object ov = node.get("outputVariables");
            if (ov instanceof List<?> list) {
                for (Object o : list) { if (o != null) outputVars.add(o.toString()); }
            }
            outputVariablesPerResult.add(outputVars);
            final String activityType = at;
            final String nodeId = nid;
            final String currentMap = variableMapJson;
            Promise<String> p = Async.function(() -> untypedActivityStub.execute(
                    activityType, String.class,
                    planJson, nodeId, currentMap, queueForActivities, workflowInputJson, null));
            promises.add(p);
        }
        if (promises.isEmpty()) return variableMapJson;
        try {
            Promise.allOf(promises).get();
        } catch (Throwable firstFailure) {
            // Wait for all parallel activities to report to Temporal before propagating failure.
            // Otherwise the workflow may complete (e.g. fallback) while other activities are still
            // running; when they report completion they get NOT_FOUND: workflow execution already completed.
            for (Promise<String> p : promises) {
                try {
                    p.get();
                } catch (Throwable ignored) {
                    // Already failed or will fail; we only need them to finish and report.
                }
            }
            throw firstFailure;
        }
        List<String> results = new ArrayList<>();
        for (Promise<String> p : promises) {
            String r = p.get();
            if (r != null && !r.isEmpty()) results.add(r);
        }
        return mergeVariableMaps(variableMapJson, results, outputVariablesPerResult);
    }
}
