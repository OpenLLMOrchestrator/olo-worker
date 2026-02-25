package com.olo.worker.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.input.model.WorkflowInput;
import com.olo.worker.activity.OloKernelActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OLO Kernel workflow implementation. Caches input, then runs the execution tree.
 * When the tree is linear (only SEQUENCE, GROUP, and leaf nodes), schedules one Temporal activity
 * per leaf node so event history shows "NODETYPE" or "PLUGIN:pluginRef".
 * When the tree has FORK/JOIN, schedules one activity per branch in parallel, then one for JOIN.
 * When the tree has TRY_CATCH, schedules try step(s) and on exception runs catch step with error in variable map.
 * Otherwise (e.g. IF, SWITCH) runs the whole tree in a single RunExecutionTree activity.
 * When the task queue ends with "-debug", activity timeouts are effectively disabled (very long duration).
 */
public class OloKernelWorkflowImpl implements OloKernelWorkflow {

    private static final Duration ACTIVITY_SCHEDULE_TO_CLOSE = Duration.ofMinutes(5);
    /** When running on a -debug queue, use a very long timeout so debugging is not interrupted. */
    private static final Duration ACTIVITY_NO_TIMEOUT_DEBUG = Duration.ofDays(365);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String run(WorkflowInput workflowInput) {
        String taskQueue = null;
        try {
            WorkflowInfo info = Workflow.getInfo();
            if (info != null) {
                taskQueue = info.getTaskQueue();
            }
        } catch (Exception ignored) {
            // Not in workflow context or SDK version difference
        }
        boolean debugQueue = taskQueue != null && taskQueue.endsWith("-debug");
        Duration activityTimeout = debugQueue ? ACTIVITY_NO_TIMEOUT_DEBUG : ACTIVITY_SCHEDULE_TO_CLOSE;

        OloKernelActivities activities = Workflow.newActivityStub(
                OloKernelActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(activityTimeout)
                        .build()
        );

        ActivityStub untypedActivityStub = Workflow.newUntypedActivityStub(
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(activityTimeout)
                        .build()
        );

        activities.processInput(workflowInput.toJson());
        String queueName = workflowInput.getRouting() != null ? workflowInput.getRouting().getPipeline() : null;
        String queueNameOrEmpty = queueName != null ? queueName : "";
        String workflowInputJson = workflowInput.toJson();

        String planJson = activities.getExecutionPlan(queueNameOrEmpty, workflowInputJson);
        if (planJson == null || !planJson.contains("\"linear\":true")) {
            Workflow.getLogger(OloKernelWorkflowImpl.class).info(
                    "Scheduling RunExecutionTree activity: tree is non-linear (plan is null or linear=false)");
            String result = activities.runExecutionTree(queueNameOrEmpty, workflowInputJson);
            return result != null ? result : "";
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> plan = MAPPER.readValue(planJson, Map.class);
            String variableMapJson = (String) plan.get("initialVariableMapJson");
            String planQueueName = (String) plan.get("queueName");
            if (planQueueName == null) planQueueName = queueNameOrEmpty;
            final String queueForActivities = planQueueName;
            @SuppressWarnings("unchecked")
            List<List<Map<String, Object>>> steps = (List<List<Map<String, Object>>>) plan.get("steps");
            @SuppressWarnings("unchecked")
            Map<String, Object> tryCatchMeta = (Map<String, Object>) plan.get("tryCatch");
            Integer catchStepIndex = tryCatchMeta != null && tryCatchMeta.get("catchStepIndex") instanceof Number
                    ? ((Number) tryCatchMeta.get("catchStepIndex")).intValue() : null;
            String errorVariable = tryCatchMeta != null && tryCatchMeta.get("errorVariable") != null
                    ? tryCatchMeta.get("errorVariable").toString() : null;
            if (steps != null && !steps.isEmpty() && variableMapJson != null) {
                int tryCatchCatchStepIndex = catchStepIndex != null && catchStepIndex >= 0 ? catchStepIndex : -1;
                try {
                    for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                        if (tryCatchCatchStepIndex >= 0 && stepIndex == tryCatchCatchStepIndex) continue;
                        List<Map<String, Object>> step = steps.get(stepIndex);
                        variableMapJson = runStep(untypedActivityStub, planJson, queueForActivities, workflowInputJson,
                                step, variableMapJson);
                        if (variableMapJson == null) variableMapJson = "{}";
                    }
                } catch (Exception e) {
                    if (tryCatchCatchStepIndex >= 0 && tryCatchCatchStepIndex < steps.size() && errorVariable != null) {
                        variableMapJson = mergeErrorIntoVariableMap(variableMapJson, errorVariable, e.getMessage());
                        variableMapJson = runStep(untypedActivityStub, planJson, queueForActivities, workflowInputJson,
                                steps.get(tryCatchCatchStepIndex), variableMapJson);
                        if (variableMapJson == null) variableMapJson = "{}";
                    } else {
                        throw e;
                    }
                }
                String result = activities.applyResultMapping(planJson, variableMapJson);
                return result != null ? result : "";
            }
            @SuppressWarnings("unchecked")
            List<Map<String, String>> nodes = (List<Map<String, String>>) plan.get("nodes");
            if (nodes == null || variableMapJson == null) {
                Workflow.getLogger(OloKernelWorkflowImpl.class).info(
                        "Scheduling RunExecutionTree activity: linear plan missing nodes or variable map");
                String result = activities.runExecutionTree(queueNameOrEmpty, workflowInputJson);
                return result != null ? result : "";
            }
            for (Map<String, String> node : nodes) {
                String activityType = node.get("activityType");
                String nodeId = node.get("nodeId");
                if (activityType == null || nodeId == null) continue;
                variableMapJson = untypedActivityStub.execute(
                        activityType,
                        String.class,
                        planJson, nodeId, variableMapJson, planQueueName, workflowInputJson);
                if (variableMapJson == null) variableMapJson = "{}";
            }
            String result = activities.applyResultMapping(planJson, variableMapJson);
            return result != null ? result : "";
        } catch (Exception e) {
            Workflow.getLogger(OloKernelWorkflowImpl.class).warn(
                    "Per-node execution failed, falling back to RunExecutionTree: {}", e.getMessage());
            Workflow.getLogger(OloKernelWorkflowImpl.class).info(
                    "Scheduling RunExecutionTree activity: fallback after per-node execution failure");
            String result = activities.runExecutionTree(queueNameOrEmpty, workflowInputJson);
            return result != null ? result : "";
        }
    }

    /**
     * Merges the base variable map with one or more activity result maps (e.g. from parallel FORK branches).
     * When outputVariablesPerResult is provided and matches the number of results, only variables that are
     * output-mapped for that plugin are taken from each result (so a model not configured in that branch
     * does not affect the merged result). Otherwise only non-null values from result maps are applied.
     */
    private static String mergeVariableMaps(String baseVariableMapJson, List<String> resultVariableMapJsonList,
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

    /**
     * Merges an error message into the variable map under the given key (for TRY_CATCH catch step).
     */
    private static String mergeErrorIntoVariableMap(String variableMapJson, String errorVariable, String errorMessage) {
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

    /**
     * Runs one step (single node or parallel nodes) and returns the updated variable map JSON.
     * For parallel steps, only variables that are output-mapped for each plugin are merged from that branch.
     */
    private static String runStep(ActivityStub untypedActivityStub, String planJson, String queueForActivities,
                                  String workflowInputJson, List<Map<String, Object>> step, String variableMapJson) {
        if (step == null || step.isEmpty()) return variableMapJson;
        if (step.size() == 1) {
            Map<String, Object> node = step.get(0);
            String activityType = node.get("activityType") != null ? node.get("activityType").toString() : null;
            String nodeId = node.get("nodeId") != null ? node.get("nodeId").toString() : null;
            if (activityType == null || nodeId == null) return variableMapJson;
            return untypedActivityStub.execute(
                    activityType, String.class,
                    planJson, nodeId, variableMapJson, queueForActivities, workflowInputJson);
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
                for (Object o : list) {
                    if (o != null) outputVars.add(o.toString());
                }
            }
            outputVariablesPerResult.add(outputVars);
            final String activityType = at;
            final String nodeId = nid;
            final String currentMap = variableMapJson;
            Promise<String> p = Async.function(() -> untypedActivityStub.execute(
                    activityType, String.class,
                    planJson, nodeId, currentMap, queueForActivities, workflowInputJson));
            promises.add(p);
        }
        if (promises.isEmpty()) return variableMapJson;
        Promise.allOf(promises).get();
        List<String> results = new ArrayList<>();
        for (Promise<String> p : promises) {
            String r = p.get();
            if (r != null && !r.isEmpty()) results.add(r);
        }
        return mergeVariableMaps(variableMapJson, results, outputVariablesPerResult);
    }
}
