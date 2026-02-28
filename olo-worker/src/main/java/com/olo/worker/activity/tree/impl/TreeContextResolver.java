package com.olo.worker.activity.tree.impl;

import com.olo.executioncontext.ExecutionConfigSnapshot;
import com.olo.executioncontext.LocalContext;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.input.model.InputItem;
import com.olo.input.model.WorkflowInput;
import com.olo.config.TenantConfigRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolve execution context (queue, config, pipeline, inputs, tenant config). */
final class TreeContextResolver {

    private static final Logger log = LoggerFactory.getLogger(TreeContextResolver.class);

    enum Status { OK, NO_CONFIG, NO_PIPELINE }

    static final class ResolvedContext {
        final Status status;
        final String tenantId, effectiveQueue, snapshotVersionId, transactionId, runId;
        final PipelineConfiguration config;
        final PipelineDefinition pipeline;
        final ExecutionTreeNode rootNode;
        final ExecutionConfigSnapshot snapshot;
        final Map<String, Object> inputValues, tenantConfigMap, nodeInstanceCache;

        ResolvedContext(Status status, String tenantId, String effectiveQueue, String snapshotVersionId,
                        String transactionId, String runId, PipelineConfiguration config, PipelineDefinition pipeline,
                        ExecutionTreeNode rootNode, ExecutionConfigSnapshot snapshot, Map<String, Object> inputValues,
                        Map<String, Object> tenantConfigMap, Map<String, Object> nodeInstanceCache) {
            this.status = status;
            this.tenantId = tenantId;
            this.effectiveQueue = effectiveQueue;
            this.snapshotVersionId = snapshotVersionId;
            this.transactionId = transactionId;
            this.runId = runId;
            this.config = config;
            this.pipeline = pipeline;
            this.rootNode = rootNode;
            this.snapshot = snapshot;
            this.inputValues = inputValues;
            this.tenantConfigMap = tenantConfigMap;
            this.nodeInstanceCache = nodeInstanceCache;
        }
    }

    static ResolvedContext resolve(String tenantId, String queueName, WorkflowInput workflowInput) {
        String requestedVersion = workflowInput.getRouting() != null ? workflowInput.getRouting().getConfigVersion() : null;
        if (requestedVersion != null) requestedVersion = requestedVersion.isBlank() ? null : requestedVersion.trim();
        TreeContextLookup.QueueAndContext qc = TreeContextLookup.getLocalContext(tenantId, queueName, requestedVersion);
        String effectiveQueue = qc.effectiveQueue;
        LocalContext localContext = qc.localContext;
        if (localContext == null) {
            log.warn("No LocalContext for tenant={} queue={} (version={}); cannot run execution tree", tenantId, effectiveQueue, requestedVersion);
            return new ResolvedContext(Status.NO_CONFIG, tenantId, effectiveQueue, null, null, null, null, null, null, null, Map.of(), Map.of(), Map.of());
        }
        PipelineConfiguration config = localContext.getPipelineConfiguration();
        if (config == null || config.getPipelines() == null || config.getPipelines().isEmpty()) {
            log.warn("No pipelines in config for queue {}", effectiveQueue);
            return new ResolvedContext(Status.NO_PIPELINE, tenantId, effectiveQueue, null, null, null, null, null, null, null, Map.of(), Map.of(), Map.of());
        }
        String snapshotVersionId = requestedVersion != null ? requestedVersion : (config.getVersion() != null ? config.getVersion() : "");
        PipelineDefinition pipeline = config.getPipelines().values().iterator().next();
        ExecutionTreeNode rootNode = pipeline.getExecutionTree();
        String transactionId = workflowInput.getRouting() != null ? workflowInput.getRouting().getTransactionId() : null;
        log.info("OloKernel runExecutionTree | transactionId={} | pipelineName={} | queue={} | tenantId={} | rootNodeId={} | rootNodeType={} | configVersion={}",
                transactionId, pipeline.getName(), effectiveQueue, tenantId, rootNode != null ? rootNode.getId() : null,
                rootNode != null && rootNode.getType() != null ? rootNode.getType().name() : null, snapshotVersionId);
        String runId = java.util.UUID.randomUUID().toString();
        ExecutionConfigSnapshot snapshot = ExecutionConfigSnapshot.of(tenantId, effectiveQueue, config, snapshotVersionId, runId);
        Map<String, Object> inputValues = new LinkedHashMap<>();
        for (InputItem item : workflowInput.getInputs()) {
            if (item != null && item.getName() != null) inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
        }
        Map<String, Object> tenantConfigMap = TenantConfigRegistry.getInstance().get(tenantId).getConfigMap();
        Map<String, Object> nodeInstanceCache = new LinkedHashMap<>();
        return new ResolvedContext(Status.OK, tenantId, effectiveQueue, snapshotVersionId, transactionId, runId, config, pipeline, rootNode, snapshot, inputValues, tenantConfigMap, nodeInstanceCache);
    }
}
