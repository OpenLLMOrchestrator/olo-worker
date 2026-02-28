package com.olo.worker.activity.node.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.TenantConfigRegistry;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.internal.features.InternalFeatures;
import com.olo.ledger.LedgerContext;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.ledger.RunLedger;
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

/** Execute a single node from a payload (plan + nodeId + variableMap), including ledger and planner handling. */
public final class NodeExecutionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        NodeExecutionPayloadResolver.ResolvedPayload r = NodeExecutionPayloadResolver.resolve(payloadJson, allowedTenantIds);
        RunLedger effectiveRunLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
        LedgerContext.setRunId(r.runId);
        long ledgerStartTime = 0L;
        if (r.isFirstNode) {
            ledgerStartTime = System.currentTimeMillis();
            String pluginVersionsJson = NodeExecutionHelpers.buildPluginVersionsJson(r.config);
            String configTreeJson = null;
            String tenantConfigJson = null;
            try {
                configTreeJson = MAPPER.writeValueAsString(r.pipeline);
                tenantConfigJson = MAPPER.writeValueAsString(TenantConfigRegistry.getInstance().get(r.tenantId).getConfigMap());
            } catch (Exception ignored) { }
            effectiveRunLedger.runStarted(r.runId, r.tenantId, r.queueName, r.queueName, r.queueName,
                    pluginVersionsJson, r.workflowInputJson, ledgerStartTime, null, null, configTreeJson, tenantConfigJson);
        }
        String runResult = null;
        String runStatus = "SUCCESS";
        try {
            ExecutionTreeNode node = ExecutionTreeNode.findNodeById(r.pipeline.getExecutionTree(), r.nodeId);
            Map<String, Object> variableMap = MAPPER.readValue(r.variableMapJson, MAP_TYPE);
            VariableEngine variableEngine = VariableEngine.fromVariableMap(r.pipeline, variableMap);
            Map<String, Object> nodeInstanceCache = new LinkedHashMap<>();
            var executor = pluginExecutorFactory.create(r.tenantId, nodeInstanceCache);
            PluginInvoker pluginInvoker = new PluginInvoker(executor);
            NodeExecutor nodeExecutor = new NodeExecutor(pluginInvoker, r.config, r.pipeline.getExecutionType(), null, r.tenantId,
                    TenantConfigRegistry.getInstance().get(r.tenantId).getConfigMap(), r.runId, dynamicNodeBuilder, nodeFeatureEnricher);
            if (node == null && r.dynamicStepsJson != null && !r.dynamicStepsJson.isBlank()) {
                ExecutionTreeNode stepNode = NodeExecutionHelpers.resolveDynamicStep(r.nodeId, r.dynamicStepsJson);
                if (stepNode != null) {
                    stepNode = nodeFeatureEnricher.enrich(stepNode, new PipelineFeatureContextImpl(r.pipeline.getScope(), r.queueName));
                    nodeExecutor.executeSingleNode(stepNode, r.pipeline, variableEngine, r.queueName);
                    runResult = MAPPER.writeValueAsString(variableEngine.getExportMap());
                    return runResult;
                }
            }
            if (node == null) throw new IllegalArgumentException("Node not found: " + r.nodeId);
            if (node.getType() == NodeType.PLANNER) {
                List<ExecutionTreeNode> steps = nodeExecutor.executePlannerOnly(node, r.pipeline, variableEngine, r.queueName);
                List<Map<String, Object>> dynamicSteps = steps.stream().map(NodeExecutionHelpers::dynamicStepFromNode).toList();
                Map<String, Object> plannerResult = new LinkedHashMap<>();
                plannerResult.put("variableMapJson", MAPPER.writeValueAsString(variableEngine.getExportMap()));
                plannerResult.put("dynamicSteps", dynamicSteps);
                runResult = MAPPER.writeValueAsString(plannerResult);
                return runResult;
            }
            nodeExecutor.executeSingleNode(node, r.pipeline, variableEngine, r.queueName);
            runResult = MAPPER.writeValueAsString(variableEngine.getExportMap());
            return runResult;
        } catch (Throwable t) {
            runStatus = "FAILED";
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        } finally {
            String runIdForEnd = LedgerContext.getRunId();
            if (runIdForEnd == null) runIdForEnd = r.runId;
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
