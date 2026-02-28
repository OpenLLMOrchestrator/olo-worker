package com.olo.worker.activity.plan.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.input.model.WorkflowInput;
import com.olo.worker.engine.ExecutionPlanBuilder;
import com.olo.worker.engine.ResultMapper;

import java.util.Map;
import java.util.Set;

/** Build execution plan JSON and apply result mapping from variable map. */
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
        String tenantId = com.olo.config.OloConfig.normalizeTenantId(workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        PlanContextResolver.ResolvedPlanContext ctx = PlanContextResolver.resolve(tenantId, queueName, workflowInput);
        if (ctx.status != PlanContextResolver.Status.OK) return "{\"linear\":false}";
        var root = ctx.rootNode;
        var plan = ExecutionPlanBuilder.buildLinearPlan(root);
        ExecutionPlanBuilder.PlanWithParallelResult parallelResult = null;
        if (plan == null || plan.isEmpty()) {
            parallelResult = ExecutionPlanBuilder.buildPlanWithParallel(root);
            if (parallelResult == null || parallelResult.getSteps().isEmpty()) return "{\"linear\":false}";
        }
        try {
            return PlanJsonSerializer.buildPlanJson(ctx, workflowInputJson, plan, parallelResult);
        } catch (Exception e) {
            return "{\"linear\":false}";
        }
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
