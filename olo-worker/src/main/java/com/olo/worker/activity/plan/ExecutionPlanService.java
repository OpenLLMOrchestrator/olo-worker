package com.olo.worker.activity.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executioncontext.LocalContext;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.input.model.WorkflowInput;
import com.olo.worker.engine.ExecutionPlanBuilder;
import com.olo.worker.engine.ResultMapper;
import com.olo.worker.engine.VariableEngine;
import io.temporal.activity.Activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Single responsibility: build execution plan JSON and apply result mapping from variable map.
 */
public final class ExecutionPlanService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<String> allowedTenantIds;

    public ExecutionPlanService(Set<String> allowedTenantIds) {
        this.allowedTenantIds = allowedTenantIds != null ? allowedTenantIds : Set.of();
    }

    public String getExecutionPlan(String queueName, String workflowInputJson) {
        WorkflowInput workflowInput;
        try {
            workflowInput = WorkflowInput.fromJson(workflowInputJson);
        } catch (Exception e) {
            return "{\"linear\":false}";
        }
        String tenantId = com.olo.config.OloConfig.normalizeTenantId(
                workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        String effectiveQueue = resolveEffectiveQueue(queueName);
        String requestedVersion = workflowInput.getRouting() != null ? workflowInput.getRouting().getConfigVersion() : null;
        if (requestedVersion != null && requestedVersion.isBlank()) requestedVersion = null;
        else if (requestedVersion != null) requestedVersion = requestedVersion.trim();
        String defaultTenantId = com.olo.config.OloConfig.normalizeTenantId(null);
        LocalContext localContext = LocalContext.forQueue(tenantId, effectiveQueue, requestedVersion);
        if (localContext == null && !defaultTenantId.equals(tenantId)) {
            localContext = LocalContext.forQueue(defaultTenantId, effectiveQueue, requestedVersion);
        }
        if (localContext == null) {
            try {
                String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null) {
                    localContext = LocalContext.forQueue(tenantId, taskQueue, requestedVersion);
                    if (localContext == null && !defaultTenantId.equals(tenantId)) {
                        localContext = LocalContext.forQueue(defaultTenantId, taskQueue, requestedVersion);
                    }
                    if (localContext != null) effectiveQueue = taskQueue;
                }
            } catch (Exception ignored) { }
        }
        if (localContext == null) {
            return "{\"linear\":false}";
        }
        PipelineConfiguration config = localContext.getPipelineConfiguration();
        if (config == null || config.getPipelines() == null || config.getPipelines().isEmpty()) {
            return "{\"linear\":false}";
        }
        PipelineDefinition pipeline = config.getPipelines().values().iterator().next();
        if (pipeline.getExecutionTree() == null) {
            return "{\"linear\":false}";
        }
        List<ExecutionPlanBuilder.PlanEntry> plan = ExecutionPlanBuilder.buildLinearPlan(pipeline.getExecutionTree());
        ExecutionPlanBuilder.PlanWithParallelResult parallelResult = null;
        if (plan == null || plan.isEmpty()) {
            parallelResult = ExecutionPlanBuilder.buildPlanWithParallel(pipeline.getExecutionTree());
            if (parallelResult == null || parallelResult.getSteps().isEmpty()) {
                return "{\"linear\":false}";
            }
        }
        try {
            Map<String, Object> inputValues = new LinkedHashMap<>();
            for (com.olo.input.model.InputItem item : workflowInput.getInputs()) {
                if (item != null && item.getName() != null) {
                    inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
                }
            }
            VariableEngine initialEngine = new VariableEngine(pipeline, inputValues);
            String initialVariableMapJson = MAPPER.writeValueAsString(initialEngine.getExportMap());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("linear", true);
            out.put("runId", UUID.randomUUID().toString());
            out.put("configJson", MAPPER.writeValueAsString(config));
            out.put("pipelineName", pipeline.getName());
            out.put("queueName", effectiveQueue);
            out.put("workflowInputJson", workflowInputJson);
            out.put("initialVariableMapJson", initialVariableMapJson);
            if (parallelResult != null) {
                List<List<Map<String, Object>>> stepsData = new ArrayList<>();
                ExecutionTreeNode treeRoot = pipeline.getExecutionTree();
                for (List<ExecutionPlanBuilder.PlanEntry> step : parallelResult.getSteps()) {
                    List<Map<String, Object>> stepNodes = new ArrayList<>();
                    for (ExecutionPlanBuilder.PlanEntry e : step) {
                        ExecutionTreeNode node = ExecutionTreeNode.findNodeById(treeRoot, e.getNodeId());
                        List<String> outputVars = node == null || node.getOutputMappings() == null
                                ? List.of()
                                : node.getOutputMappings().stream()
                                        .map(ParameterMapping::getVariable)
                                        .filter(Objects::nonNull)
                                        .toList();
                        Map<String, Object> nodeData = new LinkedHashMap<>();
                        nodeData.put("activityType", e.getActivityType());
                        nodeData.put("nodeId", e.getNodeId());
                        nodeData.put("outputVariables", outputVars);
                        stepNodes.add(nodeData);
                    }
                    stepsData.add(stepNodes);
                }
                out.put("steps", stepsData);
                if (parallelResult.getTryCatchCatchStepIndex() != null && parallelResult.getTryCatchCatchStepIndex() >= 0) {
                    Map<String, Object> tryCatchMeta = new LinkedHashMap<>();
                    tryCatchMeta.put("catchStepIndex", parallelResult.getTryCatchCatchStepIndex());
                    if (parallelResult.getTryCatchErrorVariable() != null) {
                        tryCatchMeta.put("errorVariable", parallelResult.getTryCatchErrorVariable());
                    }
                    out.put("tryCatch", tryCatchMeta);
                }
            } else {
                List<Map<String, String>> nodes = new ArrayList<>();
                for (ExecutionPlanBuilder.PlanEntry e : plan) {
                    nodes.add(Map.of("activityType", e.getActivityType(), "nodeId", e.getNodeId()));
                }
                out.put("nodes", nodes);
            }
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"linear\":false}";
        }
    }

    private static String resolveEffectiveQueue(String queueName) {
        String effectiveQueue = queueName;
        if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
            try {
                String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null && taskQueue.endsWith("-debug")) effectiveQueue = taskQueue;
            } catch (Exception ignored) { }
        }
        return effectiveQueue;
    }

    public String applyResultMapping(String planJson, String variableMapJson) {
        if (planJson == null || variableMapJson == null) return "";
        try {
            Map<String, Object> plan = MAPPER.readValue(planJson, MAP_TYPE);
            if (!Boolean.TRUE.equals(plan.get("linear"))) return "";
            String configJson = (String) plan.get("configJson");
            String pipelineName = (String) plan.get("pipelineName");
            if (configJson == null || pipelineName == null) return "";
            PipelineConfiguration config = MAPPER.readValue(configJson, PipelineConfiguration.class);
            PipelineDefinition pipeline = config.getPipelines() != null ? config.getPipelines().get(pipelineName) : null;
            if (pipeline == null) return "";
            Map<String, Object> variableMap = MAPPER.readValue(variableMapJson, MAP_TYPE);
            return ResultMapper.applyFromMap(pipeline, variableMap);
        } catch (Exception e) {
            return "";
        }
    }
}
