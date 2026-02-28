package com.olo.worker.activity.node.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.OloConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.input.model.WorkflowInput;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NodeExecutionPayloadResolver {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final class ResolvedPayload {
        final String tenantId, queueName, workflowInputJson, dynamicStepsJson;
        final PipelineConfiguration config;
        final PipelineDefinition pipeline;
        final String nodeId, variableMapJson, runId;
        final boolean isFirstNode;

        ResolvedPayload(String tenantId, String queueName, String workflowInputJson, String dynamicStepsJson,
                        PipelineConfiguration config, PipelineDefinition pipeline, String nodeId,
                        String variableMapJson, String runId, boolean isFirstNode) {
            this.tenantId = tenantId;
            this.queueName = queueName;
            this.workflowInputJson = workflowInputJson;
            this.dynamicStepsJson = dynamicStepsJson;
            this.config = config;
            this.pipeline = pipeline;
            this.nodeId = nodeId;
            this.variableMapJson = variableMapJson;
            this.runId = runId;
            this.isFirstNode = isFirstNode;
        }
    }

    static ResolvedPayload resolve(String payloadJson, Set<String> allowedTenantIds) {
        Map<String, Object> payload;
        try { payload = MAPPER.readValue(payloadJson, MAP_TYPE); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid executeNode payload: " + e.getMessage(), e);
        }
        String planJson = (String) payload.get("planJson");
        String nodeId = (String) payload.get("nodeId");
        String variableMapJson = (String) payload.get("variableMapJson");
        String queueName = payload.get("queueName") != null ? payload.get("queueName").toString() : "";
        String workflowInputJson = (String) payload.get("workflowInputJson");
        String dynamicStepsJson = (String) payload.get("dynamicStepsJson");
        if (planJson == null || nodeId == null || variableMapJson == null || workflowInputJson == null)
            throw new IllegalArgumentException("executeNode payload missing required fields");
        WorkflowInput workflowInput = WorkflowInput.fromJson(workflowInputJson);
        String tenantId = OloConfig.normalizeTenantId(workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId))
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        Map<String, Object> plan;
        try { plan = MAPPER.readValue(planJson, MAP_TYPE); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid planJson: " + e.getMessage(), e);
        }
        String configJson = (String) plan.get("configJson");
        String pipelineName = (String) plan.get("pipelineName");
        if (configJson == null || pipelineName == null) throw new IllegalArgumentException("planJson missing configJson or pipelineName");
        PipelineConfiguration config;
        try { config = MAPPER.readValue(configJson, PipelineConfiguration.class); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid configJson: " + e.getMessage(), e);
        }
        PipelineDefinition pipeline = config.getPipelines() != null ? config.getPipelines().get(pipelineName) : null;
        if (pipeline == null || pipeline.getExecutionTree() == null) throw new IllegalArgumentException("Pipeline or execution tree not found");
        String planRunId = plan.get("runId") != null ? plan.get("runId").toString() : null;
        String runId = (planRunId != null && !planRunId.isBlank()) ? planRunId : UUID.randomUUID().toString();
        boolean isFirstNode = NodeExecutionHelpers.isFirstNodeInPlan(plan, nodeId);
        return new ResolvedPayload(tenantId, queueName, workflowInputJson, dynamicStepsJson, config, pipeline, nodeId, variableMapJson, runId, isFirstNode);
    }
}
