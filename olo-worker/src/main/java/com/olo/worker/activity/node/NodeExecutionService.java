package com.olo.worker.activity.node;

import com.olo.config.TenantConfigRegistry;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.input.model.WorkflowInput;
import com.olo.ledger.LedgerContext;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.ledger.RunLedger;
import com.olo.internal.features.InternalFeatures;
import com.olo.node.NodeFeatureEnricher;
import com.olo.node.PipelineFeatureContextImpl;
import com.olo.plugin.PluginExecutorFactory;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.NodeExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single responsibility: execute a single node from a payload (plan + nodeId + variableMap), including ledger and planner handling.
 */
public final class NodeExecutionService {

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final Set<String> allowedTenantIds;
    private final RunLedger runLedger;
    private final PluginExecutorFactory pluginExecutorFactory;
    private final com.olo.node.DynamicNodeBuilder dynamicNodeBuilder;
    private final NodeFeatureEnricher nodeFeatureEnricher;

    public NodeExecutionService(Set<String> allowedTenantIds, RunLedger runLedger,
                                PluginExecutorFactory pluginExecutorFactory,
                                com.olo.node.DynamicNodeBuilder dynamicNodeBuilder,
                                NodeFeatureEnricher nodeFeatureEnricher) {
        this.allowedTenantIds = allowedTenantIds != null ? allowedTenantIds : Set.of();
        this.runLedger = runLedger;
        this.pluginExecutorFactory = pluginExecutorFactory;
        this.dynamicNodeBuilder = dynamicNodeBuilder;
        this.nodeFeatureEnricher = nodeFeatureEnricher != null ? nodeFeatureEnricher : (n, c) -> n;
    }

    public String executeNode(String payloadJson) {
        Map<String, Object> payload;
        try {
            payload = MAPPER.readValue(payloadJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid executeNode payload: " + e.getMessage(), e);
        }
        String planJson = (String) payload.get("planJson");
        String nodeId = (String) payload.get("nodeId");
        String variableMapJson = (String) payload.get("variableMapJson");
        String queueName = payload.get("queueName") != null ? payload.get("queueName").toString() : "";
        String workflowInputJson = (String) payload.get("workflowInputJson");
        String dynamicStepsJson = (String) payload.get("dynamicStepsJson");
        if (planJson == null || nodeId == null || variableMapJson == null || workflowInputJson == null) {
            throw new IllegalArgumentException("executeNode payload missing planJson, nodeId, variableMapJson or workflowInputJson");
        }
        WorkflowInput workflowInput = WorkflowInput.fromJson(workflowInputJson);
        String tenantId = com.olo.config.OloConfig.normalizeTenantId(
                workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        Map<String, Object> plan;
        try {
            plan = MAPPER.readValue(planJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid planJson: " + e.getMessage(), e);
        }
        String configJson = (String) plan.get("configJson");
        String pipelineName = (String) plan.get("pipelineName");
        if (configJson == null || pipelineName == null) {
            throw new IllegalArgumentException("planJson missing configJson or pipelineName");
        }
        PipelineConfiguration config;
        try {
            config = MAPPER.readValue(configJson, PipelineConfiguration.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid configJson: " + e.getMessage(), e);
        }
        PipelineDefinition pipeline = config.getPipelines() != null ? config.getPipelines().get(pipelineName) : null;
        if (pipeline == null || pipeline.getExecutionTree() == null) {
            throw new IllegalArgumentException("Pipeline or execution tree not found");
        }
        String planRunId = plan.get("runId") != null ? plan.get("runId").toString() : null;
        String runId = (planRunId != null && !planRunId.isBlank()) ? planRunId : UUID.randomUUID().toString();
        boolean isFirstNode = NodeExecutionHelpers.isFirstNodeInPlan(plan, nodeId);

        RunLedger effectiveRunLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
        LedgerContext.setRunId(runId);
        long ledgerStartTime = 0L;
        if (isFirstNode) {
            ledgerStartTime = System.currentTimeMillis();
            String pluginVersionsJson = NodeExecutionHelpers.buildPluginVersionsJson(config);
            String configTreeJson = null;
            String tenantConfigJson = null;
            try {
                configTreeJson = MAPPER.writeValueAsString(pipeline);
                tenantConfigJson = MAPPER.writeValueAsString(TenantConfigRegistry.getInstance().get(tenantId).getConfigMap());
            } catch (Exception ignored) { }
            effectiveRunLedger.runStarted(runId, tenantId, queueName, queueName, queueName, pluginVersionsJson, workflowInputJson, ledgerStartTime, null, null, configTreeJson, tenantConfigJson);
        }

        String runResult = null;
        String runStatus = "SUCCESS";
        try {
            ExecutionTreeNode node = ExecutionTreeNode.findNodeById(pipeline.getExecutionTree(), nodeId);
            Map<String, Object> variableMap = MAPPER.readValue(variableMapJson, MAP_TYPE);
            VariableEngine variableEngine = VariableEngine.fromVariableMap(pipeline, variableMap);
            Map<String, Object> nodeInstanceCache = new LinkedHashMap<>();
            var executor = pluginExecutorFactory.create(tenantId, nodeInstanceCache);
            PluginInvoker pluginInvoker = new PluginInvoker(executor);
            NodeExecutor nodeExecutor = new NodeExecutor(
                    pluginInvoker, config, pipeline.getExecutionType(), null, tenantId,
                    TenantConfigRegistry.getInstance().get(tenantId).getConfigMap(), runId, dynamicNodeBuilder, nodeFeatureEnricher);

            if (node == null && dynamicStepsJson != null && !dynamicStepsJson.isBlank()) {
                ExecutionTreeNode stepNode = NodeExecutionHelpers.resolveDynamicStep(nodeId, dynamicStepsJson);
                if (stepNode != null) {
                    stepNode = nodeFeatureEnricher.enrich(stepNode, new PipelineFeatureContextImpl(pipeline.getScope(), queueName));
                    nodeExecutor.executeSingleNode(stepNode, pipeline, variableEngine, queueName);
                    runResult = MAPPER.writeValueAsString(variableEngine.getExportMap());
                    return runResult;
                }
            }
            if (node == null) {
                throw new IllegalArgumentException("Node not found: " + nodeId);
            }
            if (node.getType() == NodeType.PLANNER) {
                List<ExecutionTreeNode> steps = nodeExecutor.executePlannerOnly(node, pipeline, variableEngine, queueName);
                List<Map<String, Object>> dynamicSteps = steps.stream()
                        .map(NodeExecutionHelpers::dynamicStepFromNode)
                        .toList();
                Map<String, Object> plannerResult = new LinkedHashMap<>();
                plannerResult.put("variableMapJson", MAPPER.writeValueAsString(variableEngine.getExportMap()));
                plannerResult.put("dynamicSteps", dynamicSteps);
                runResult = MAPPER.writeValueAsString(plannerResult);
                return runResult;
            }
            nodeExecutor.executeSingleNode(node, pipeline, variableEngine, queueName);
            runResult = MAPPER.writeValueAsString(variableEngine.getExportMap());
            return runResult;
        } catch (Throwable t) {
            runStatus = "FAILED";
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        } finally {
            String runIdForEnd = LedgerContext.getRunId();
            if (runIdForEnd == null) runIdForEnd = runId;
            if (runIdForEnd != null) {
                long endTime = System.currentTimeMillis();
                Long durationMs = ledgerStartTime > 0 ? (endTime - ledgerStartTime) : null;
                effectiveRunLedger.runEnded(runIdForEnd, endTime, runResult != null ? runResult : "", runStatus, durationMs, null, null, null, null, "USD");
            }
            LedgerContext.clear();
            InternalFeatures.clearLedgerForRun();
        }
    }

}
