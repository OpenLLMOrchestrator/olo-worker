package com.olo.worker.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.input.model.WorkflowInput;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.worker.activity.node.NodeExecutionService;
import com.olo.worker.activity.plan.ExecutionPlanService;
import com.olo.worker.activity.plugin.PluginExecutionService;
import com.olo.worker.activity.tree.TreeRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OLO Kernel activity implementation. Thin facade that delegates to dedicated services:
 * session/input, plugin execution, execution plan, single-node execution, and tree execution.
 */
public class OloKernelActivitiesImpl implements OloKernelActivities {

    private static final Logger log = LoggerFactory.getLogger(OloKernelActivitiesImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OloSessionCache sessionCache;
    private final Set<String> allowedTenantIds;
    private final ExecutionPlanService planService;
    private final PluginExecutionService pluginService;
    private final NodeExecutionService nodeExecutionService;
    private final TreeRunService treeRunService;

    public OloKernelActivitiesImpl(OloSessionCache sessionCache, List<String> allowedTenantIds, com.olo.ledger.RunLedger runLedger,
                                   com.olo.plugin.PluginExecutorFactory pluginExecutorFactory,
                                   DynamicNodeBuilder dynamicNodeBuilder,
                                   NodeFeatureEnricher nodeFeatureEnricher) {
        this.sessionCache = sessionCache;
        this.allowedTenantIds = allowedTenantIds != null ? Set.copyOf(allowedTenantIds) : Set.of();
        this.planService = new ExecutionPlanService(this.allowedTenantIds);
        this.pluginService = new PluginExecutionService(pluginExecutorFactory);
        this.nodeExecutionService = new NodeExecutionService(this.allowedTenantIds, runLedger, pluginExecutorFactory, dynamicNodeBuilder, nodeFeatureEnricher);
        this.treeRunService = new TreeRunService(this.allowedTenantIds, sessionCache, runLedger, pluginExecutorFactory, dynamicNodeBuilder, nodeFeatureEnricher);
    }

    @Override
    public String processInput(String workflowInputJson) {
        WorkflowInput input = WorkflowInput.fromJson(workflowInputJson);
        sessionCache.cacheUpdate(input);
        String transactionId = input.getRouting() != null ? input.getRouting().getTransactionId() : null;
        String pipeline = input.getRouting() != null ? input.getRouting().getPipeline() : null;
        String transactionType = input.getRouting() != null && input.getRouting().getTransactionType() != null
                ? input.getRouting().getTransactionType().name() : null;
        String configVersion = input.getRouting() != null ? input.getRouting().getConfigVersion() : null;
        String tenantId = input.getContext() != null ? input.getContext().getTenantId() : null;
        String sessionId = input.getContext() != null ? input.getContext().getSessionId() : null;
        String version = input.getVersion();
        int inputsCount = input.getInputs() != null ? input.getInputs().size() : 0;
        String ragTag = input.getMetadata() != null ? input.getMetadata().getRagTag() : null;
        Long metadataTimestamp = input.getMetadata() != null ? input.getMetadata().getTimestamp() : null;
        log.info("OloKernel processed workflow input | transactionId={} | pipeline={} | transactionType={} | tenantId={} | sessionId={} | version={} | configVersion={} | inputsCount={} | ragTag={} | metadataTimestamp={}",
                transactionId, pipeline, transactionType, tenantId, sessionId, version, configVersion, inputsCount, ragTag, metadataTimestamp);
        return transactionId != null ? transactionId : "unknown";
    }

    @Override
    public String executePlugin(String pluginId, String inputsJson) {
        return pluginService.executePlugin(OloConfig.normalizeTenantId(null), pluginId, inputsJson, null, null);
    }

    @Override
    public String getChatResponse(String pluginId, String prompt) {
        return pluginService.getChatResponse(pluginId, prompt);
    }

    @Override
    public String getExecutionPlan(String queueName, String workflowInputJson) {
        return planService.getExecutionPlan(queueName, workflowInputJson);
    }

    @Override
    public String applyResultMapping(String planJson, String variableMapJson) {
        return planService.applyResultMapping(planJson, variableMapJson);
    }

    @Override
    public String executeNode(String activityType, String planJson, String nodeId, String variableMapJson,
                              String queueName, String workflowInputJson, String dynamicStepsJson) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("planJson", planJson);
        payload.put("nodeId", nodeId);
        payload.put("variableMapJson", variableMapJson);
        payload.put("queueName", queueName != null ? queueName : "");
        payload.put("workflowInputJson", workflowInputJson);
        if (dynamicStepsJson != null && !dynamicStepsJson.isBlank()) {
            payload.put("dynamicStepsJson", dynamicStepsJson);
        }
        try {
            return nodeExecutionService.executeNode(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build executeNode payload", e);
        }
    }

    @Override
    public String runExecutionTree(String queueName, String workflowInputJson) {
        return treeRunService.runExecutionTree(queueName, workflowInputJson);
    }
}
