package com.olo.worker.activity.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.config.TenantConfigRegistry;
import com.olo.executioncontext.ExecutionConfigSnapshot;
import com.olo.executioncontext.LocalContext;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.input.model.WorkflowInput;
import com.olo.ledger.LedgerContext;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.ledger.RunLedger;
import com.olo.internal.features.InternalFeatures;
import com.olo.plugin.PluginExecutor;
import com.olo.plugin.PluginExecutorFactory;
import com.olo.worker.engine.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Single responsibility: run full execution tree for a workflow input (context resolution, engine run, ledger).
 */
public final class TreeRunService {

    private static final Logger log = LoggerFactory.getLogger(TreeRunService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<String> allowedTenantIds;
    private final OloSessionCache sessionCache;
    private final RunLedger runLedger;
    private final PluginExecutorFactory pluginExecutorFactory;
    private final com.olo.node.DynamicNodeBuilder dynamicNodeBuilder;
    private final com.olo.node.NodeFeatureEnricher nodeFeatureEnricher;

    public TreeRunService(Set<String> allowedTenantIds, OloSessionCache sessionCache, RunLedger runLedger,
                          PluginExecutorFactory pluginExecutorFactory,
                          com.olo.node.DynamicNodeBuilder dynamicNodeBuilder,
                          com.olo.node.NodeFeatureEnricher nodeFeatureEnricher) {
        this.allowedTenantIds = allowedTenantIds != null ? allowedTenantIds : Set.of();
        this.sessionCache = sessionCache;
        this.runLedger = runLedger;
        this.pluginExecutorFactory = pluginExecutorFactory;
        this.dynamicNodeBuilder = dynamicNodeBuilder;
        this.nodeFeatureEnricher = nodeFeatureEnricher;
    }

    public String runExecutionTree(String queueName, String workflowInputJson) {
        int inputLen = workflowInputJson != null ? workflowInputJson.length() : 0;
        String inputSnippet = workflowInputJson != null && workflowInputJson.length() > 300
                ? workflowInputJson.substring(0, 300) + "...[truncated]"
                : (workflowInputJson != null ? workflowInputJson : "");
        log.info("Activity entry | runExecutionTree | queue={} | workflowInputLength={} | workflowInputSnippet={}",
                queueName != null ? queueName : "", inputLen, inputSnippet);
        WorkflowInput workflowInput;
        try {
            workflowInput = WorkflowInput.fromJson(workflowInputJson);
        } catch (Exception e) {
            log.warn("Invalid workflow input JSON for runExecutionTree", e);
            log.info("Activity exit | runExecutionTree | status=INVALID_INPUT | resultLength=0");
            return "";
        }
        String tenantId = OloConfig.normalizeTenantId(
                workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        log.info("RunExecutionTree activity started | invoked from workflow | queue={} | tenantId={}",
                queueName != null ? queueName : "", tenantId);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        sessionCache.incrActiveWorkflows(tenantId);
        try {
            return doRunExecutionTree(tenantId, queueName, workflowInput, workflowInputJson);
        } finally {
            sessionCache.decrActiveWorkflows(tenantId);
        }
    }

    private String doRunExecutionTree(String tenantId, String queueName, WorkflowInput workflowInput, String workflowInputJson) {
        String effectiveQueue = resolveEffectiveQueue(queueName);
        String requestedVersion = workflowInput.getRouting() != null ? workflowInput.getRouting().getConfigVersion() : null;
        if (requestedVersion != null) requestedVersion = requestedVersion.isBlank() ? null : requestedVersion.trim();
        String defaultTenantId = OloConfig.normalizeTenantId(null);
        LocalContext localContext = LocalContext.forQueue(tenantId, effectiveQueue, requestedVersion);
        if (localContext == null && !defaultTenantId.equals(tenantId)) {
            localContext = LocalContext.forQueue(defaultTenantId, effectiveQueue, requestedVersion);
            if (localContext != null) log.debug("No config for tenant={}; using default tenant config for queue={}", tenantId, effectiveQueue);
        }
        if (localContext == null) {
            try {
                String taskQueue = io.temporal.activity.Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null) {
                    localContext = LocalContext.forQueue(tenantId, taskQueue, requestedVersion);
                    if (localContext == null && !defaultTenantId.equals(tenantId)) {
                        localContext = LocalContext.forQueue(defaultTenantId, taskQueue, requestedVersion);
                        if (localContext != null) log.debug("No config for tenant={}; using default tenant config for queue={}", tenantId, taskQueue);
                    }
                    if (localContext != null) effectiveQueue = taskQueue;
                }
            } catch (Exception ignored) { }
        }
        if (localContext == null) {
            log.warn("No LocalContext for tenant={} queue={} (version={}); cannot run execution tree", tenantId, effectiveQueue, requestedVersion);
            log.info("Activity exit | runExecutionTree | status=NO_CONFIG | resultLength=0");
            return "";
        }
        PipelineConfiguration config = localContext.getPipelineConfiguration();
        if (config == null || config.getPipelines() == null || config.getPipelines().isEmpty()) {
            log.warn("No pipelines in config for queue {}", effectiveQueue);
            log.info("Activity exit | runExecutionTree | status=NO_PIPELINE | resultLength=0");
            return "";
        }
        String snapshotVersionId = requestedVersion != null ? requestedVersion : (config.getVersion() != null ? config.getVersion() : "");
        com.olo.executiontree.config.PipelineDefinition pipeline = config.getPipelines().values().iterator().next();
        ExecutionTreeNode rootNode = pipeline.getExecutionTree();
        String pipelineName = pipeline.getName();
        String transactionId = workflowInput.getRouting() != null ? workflowInput.getRouting().getTransactionId() : null;
        log.info("OloKernel runExecutionTree | transactionId={} | pipelineName={} | queue={} | tenantId={} | rootNodeId={} | rootNodeType={} | configVersion={}",
                transactionId, pipelineName, effectiveQueue, tenantId, rootNode != null ? rootNode.getId() : null, rootNode != null && rootNode.getType() != null ? rootNode.getType().name() : null, snapshotVersionId);

        RunLedger effectiveRunLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
        if (runLedger == null) {
            log.info("Run ledger was null; using no-op ledger so runId is set and node ledger features can run.");
        }
        String runId = UUID.randomUUID().toString();
        ExecutionConfigSnapshot snapshot = ExecutionConfigSnapshot.of(tenantId, effectiveQueue, config, snapshotVersionId, runId);
        Map<String, Object> inputValues = new LinkedHashMap<>();
        for (com.olo.input.model.InputItem item : workflowInput.getInputs()) {
            if (item != null && item.getName() != null) {
                inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
            }
        }
        Map<String, Object> tenantConfigMap = TenantConfigRegistry.getInstance().get(tenantId).getConfigMap();
        Map<String, Object> nodeInstanceCache = new LinkedHashMap<>();

        long ledgerStartTime = System.currentTimeMillis();
        LedgerContext.setRunId(runId);
        String pluginVersionsJson = buildPluginVersionsJson(config);
        String configTreeJson = null;
        String tenantConfigJson = null;
        try {
            configTreeJson = MAPPER.writeValueAsString(pipeline);
            tenantConfigJson = MAPPER.writeValueAsString(tenantConfigMap);
        } catch (Exception ignored) { }
        effectiveRunLedger.runStarted(runId, tenantId, effectiveQueue, snapshotVersionId, snapshotVersionId, pluginVersionsJson, workflowInputJson, ledgerStartTime, null, null, configTreeJson, tenantConfigJson);

        String runResult = null;
        String runStatus = "FAILED";
        Throwable runFailure = null;
        try {
            PluginExecutor executor = pluginExecutorFactory.create(tenantId, nodeInstanceCache);
            runResult = ExecutionEngine.run(snapshot, inputValues, executor, tenantConfigMap, dynamicNodeBuilder, nodeFeatureEnricher);
            runStatus = "SUCCESS";
            log.info("Execution tree exit | transactionId={} | status={} | resultLength={}", transactionId, runStatus, runResult != null ? runResult.length() : 0);
            log.info("Activity exit | runExecutionTree | status={} | resultLength={}", runStatus, runResult != null ? runResult.length() : 0);
            return runResult != null ? runResult : "";
        } catch (IllegalArgumentException e) {
            log.warn("Execution engine validation failed: {}", e.getMessage());
            runFailure = e;
            log.info("Activity exit | runExecutionTree | status=VALIDATION_FAILED | resultLength=0");
            return "";
        } catch (Throwable t) {
            runFailure = t;
            log.info("Execution tree exit | transactionId={} | status=FAILED | error={}", transactionId, t.getMessage());
            log.info("Activity exit | runExecutionTree | status=FAILED | resultLength=0");
            throw t;
        } finally {
            String runIdForEnd = LedgerContext.getRunId();
            if (runIdForEnd == null) runIdForEnd = runId;
            if (runIdForEnd != null) {
                long endTime = System.currentTimeMillis();
                Long durationMs = ledgerStartTime > 0 ? (endTime - ledgerStartTime) : null;
                String errMsg = runFailure != null ? (runFailure.getMessage() != null ? runFailure.getMessage() : runFailure.getClass().getName()) : null;
                String failureStage = runFailure != null ? runFailure.getClass().getSimpleName() : null;
                effectiveRunLedger.runEnded(runIdForEnd, endTime, runResult, runStatus, durationMs, errMsg, failureStage, null, null, "USD");
            }
            LedgerContext.clear();
            InternalFeatures.clearLedgerForRun();
        }
    }

    private static String resolveEffectiveQueue(String queueName) {
        String effectiveQueue = queueName;
        if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
            try {
                String taskQueue = io.temporal.activity.Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null && taskQueue.endsWith("-debug")) effectiveQueue = taskQueue;
            } catch (Exception ignored) { }
        }
        return effectiveQueue;
    }

    private static String buildPluginVersionsJson(PipelineConfiguration config) {
        Map<String, String> versions = new TreeMap<>();
        if (config != null && config.getPipelines() != null) {
            for (PipelineDefinition def : config.getPipelines().values()) {
                if (def == null || def.getExecutionTree() == null) continue;
                collectPluginRefs(def.getExecutionTree(), versions);
            }
        }
        try {
            return MAPPER.writeValueAsString(versions);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void collectPluginRefs(ExecutionTreeNode node, Map<String, String> out) {
        if (node == null) return;
        if (node.getType() == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
            out.putIfAbsent(node.getPluginRef(), "?");
        }
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) {
                collectPluginRefs(child, out);
            }
        }
    }
}
