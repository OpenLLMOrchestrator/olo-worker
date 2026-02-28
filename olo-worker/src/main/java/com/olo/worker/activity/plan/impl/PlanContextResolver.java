package com.olo.worker.activity.plan.impl;

import com.olo.config.OloConfig;
import com.olo.executioncontext.LocalContext;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.input.model.InputItem;
import com.olo.input.model.WorkflowInput;
import io.temporal.activity.Activity;

import java.util.LinkedHashMap;
import java.util.Map;

final class PlanContextResolver {

    enum Status { OK, NO_CONFIG, NO_PIPELINE, NO_TREE }

    static final class ResolvedPlanContext {
        final Status status;
        final String tenantId;
        final String effectiveQueue;
        final PipelineConfiguration config;
        final PipelineDefinition pipeline;
        final ExecutionTreeNode rootNode;
        final Map<String, Object> inputValues;

        ResolvedPlanContext(Status status, String tenantId, String effectiveQueue,
                            PipelineConfiguration config, PipelineDefinition pipeline,
                            ExecutionTreeNode rootNode, Map<String, Object> inputValues) {
            this.status = status;
            this.tenantId = tenantId;
            this.effectiveQueue = effectiveQueue;
            this.config = config;
            this.pipeline = pipeline;
            this.rootNode = rootNode;
            this.inputValues = inputValues;
        }
    }

    static ResolvedPlanContext resolve(String tenantId, String queueName, WorkflowInput workflowInput) {
        String effectiveQueue = resolveEffectiveQueue(queueName);
        String requestedVersion = workflowInput.getRouting() != null ? workflowInput.getRouting().getConfigVersion() : null;
        if (requestedVersion != null) requestedVersion = requestedVersion.isBlank() ? null : requestedVersion.trim();
        String defaultTenantId = OloConfig.normalizeTenantId(null);
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
        if (localContext == null) return new ResolvedPlanContext(Status.NO_CONFIG, tenantId, effectiveQueue, null, null, null, Map.of());
        PipelineConfiguration config = localContext.getPipelineConfiguration();
        if (config == null || config.getPipelines() == null || config.getPipelines().isEmpty()) {
            return new ResolvedPlanContext(Status.NO_PIPELINE, tenantId, effectiveQueue, null, null, null, Map.of());
        }
        PipelineDefinition pipeline = config.getPipelines().values().iterator().next();
        ExecutionTreeNode rootNode = pipeline.getExecutionTree();
        if (rootNode == null) return new ResolvedPlanContext(Status.NO_TREE, tenantId, effectiveQueue, config, pipeline, null, Map.of());
        Map<String, Object> inputValues = new LinkedHashMap<>();
        for (InputItem item : workflowInput.getInputs()) {
            if (item != null && item.getName() != null) inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
        }
        return new ResolvedPlanContext(Status.OK, tenantId, effectiveQueue, config, pipeline, rootNode, inputValues);
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
}
