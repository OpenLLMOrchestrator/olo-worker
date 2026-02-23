package com.olo.worker.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.config.TenantConfig;
import com.olo.config.TenantConfigRegistry;
import com.olo.executioncontext.ExecutionConfigSnapshot;
import com.olo.executioncontext.LocalContext;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.input.model.WorkflowInput;
import com.olo.ledger.LedgerContext;
import com.olo.ledger.RunLedger;
import com.olo.ledger.RunLevelLedgerFeature;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.PluginRegistry;
import com.olo.worker.engine.ExecutionEngine;
import com.olo.worker.engine.PluginInvoker;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * OLO Kernel activity implementation. During the initialize phase, calls
 * {@link OloSessionCache#cacheUpdate(WorkflowInput)} to serialize and push input to Redis.
 * Can execute plugins (e.g. model executor) via {@link #executePlugin(String, String)}.
 */
public class OloKernelActivitiesImpl implements OloKernelActivities {

    private static final Logger log = LoggerFactory.getLogger(OloKernelActivitiesImpl.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OloSessionCache sessionCache;
    private final Set<String> allowedTenantIds;
    private final RunLedger runLedger;

    public OloKernelActivitiesImpl(OloSessionCache sessionCache, List<String> allowedTenantIds) {
        this(sessionCache, allowedTenantIds, null);
    }

    public OloKernelActivitiesImpl(OloSessionCache sessionCache, List<String> allowedTenantIds, RunLedger runLedger) {
        this.sessionCache = sessionCache;
        this.allowedTenantIds = allowedTenantIds != null ? Set.copyOf(allowedTenantIds) : Set.of();
        this.runLedger = runLedger;
    }

    /** @deprecated Use {@link #OloKernelActivitiesImpl(OloSessionCache, List)}. */
    @Deprecated
    public OloKernelActivitiesImpl(OloSessionCache sessionCache) {
        this(sessionCache, List.of());
    }

    @Override
    public String processInput(String workflowInputJson) {
        WorkflowInput input = WorkflowInput.fromJson(workflowInputJson);
        sessionCache.cacheUpdate(input);
        String transactionId = input.getRouting() != null ? input.getRouting().getTransactionId() : null;
        log.info("OloKernel processed workflow input, transactionId: {}", transactionId);
        return transactionId != null ? transactionId : "unknown";
    }

    @Override
    public String executePlugin(String pluginId, String inputsJson) {
        return executePlugin(OloConfig.normalizeTenantId(null), pluginId, inputsJson);
    }

    /**
     * Executes a plugin for the given tenant (tenant-scoped plugin resolution).
     */
    private String executePlugin(String tenantId, String pluginId, String inputsJson) {
        ModelExecutorPlugin plugin = PluginRegistry.getInstance().getModelExecutor(tenantId, pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("No model-executor plugin registered for tenant=" + tenantId + " id=" + pluginId);
        }
        Map<String, Object> inputs;
        try {
            inputs = MAPPER.readValue(inputsJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid inputs JSON for plugin " + pluginId + ": " + e.getMessage(), e);
        }
        TenantConfig tenantConfig = TenantConfigRegistry.getInstance().get(tenantId);
        try {
            Map<String, Object> outputs = plugin.execute(inputs, tenantConfig);
            return MAPPER.writeValueAsString(outputs != null ? outputs : Map.of());
        } catch (Exception e) {
            log.error("Plugin execution failed: tenant={} pluginId={}", tenantId, pluginId, e);
            throw new RuntimeException("Plugin execution failed: " + pluginId + " - " + e.getMessage(), e);
        }
    }

    @Override
    public String getChatResponse(String pluginId, String prompt) {
        String inputsJson;
        try {
            inputsJson = MAPPER.writeValueAsString(Map.of("prompt", prompt != null ? prompt : ""));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to build prompt input", e);
        }
        String outputsJson = executePlugin(pluginId, inputsJson);
        try {
            Map<String, Object> outputs = MAPPER.readValue(outputsJson, MAP_TYPE);
            Object text = outputs != null ? outputs.get("responseText") : null;
            return text != null ? text.toString() : "";
        } catch (Exception e) {
            log.warn("Failed to parse responseText from plugin {} output", pluginId, e);
            return "";
        }
    }

    @Override
    public String runExecutionTree(String queueName, String workflowInputJson) {
        WorkflowInput workflowInput;
        try {
            workflowInput = WorkflowInput.fromJson(workflowInputJson);
        } catch (Exception e) {
            log.warn("Invalid workflow input JSON for runExecutionTree", e);
            return "";
        }
        String tenantId = OloConfig.normalizeTenantId(
                workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        sessionCache.incrActiveWorkflows(tenantId);
        try {
            String effectiveQueue = queueName;
            if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
                try {
                    String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                    if (taskQueue != null && taskQueue.endsWith("-debug")) {
                        effectiveQueue = taskQueue;
                    }
                } catch (Exception e) {
                    // Not in activity context
                }
            }
            String requestedVersion = workflowInput.getRouting() != null ? workflowInput.getRouting().getConfigVersion() : null;
            if (requestedVersion != null) {
                requestedVersion = requestedVersion.isBlank() ? null : requestedVersion.trim();
            }
            String defaultTenantId = OloConfig.normalizeTenantId(null);
            LocalContext localContext = LocalContext.forQueue(tenantId, effectiveQueue, requestedVersion);
            if (localContext == null && !defaultTenantId.equals(tenantId)) {
                localContext = LocalContext.forQueue(defaultTenantId, effectiveQueue, requestedVersion);
                if (localContext != null) {
                    log.debug("No config for tenant={}; using default tenant config for queue={}", tenantId, effectiveQueue);
                }
            }
            if (localContext == null) {
                try {
                    String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                    if (taskQueue != null) {
                        localContext = LocalContext.forQueue(tenantId, taskQueue, requestedVersion);
                        if (localContext == null && !defaultTenantId.equals(tenantId)) {
                            localContext = LocalContext.forQueue(defaultTenantId, taskQueue, requestedVersion);
                            if (localContext != null) {
                                log.debug("No config for tenant={}; using default tenant config for queue={}", tenantId, taskQueue);
                            }
                        }
                        if (localContext != null) {
                            effectiveQueue = taskQueue;
                        }
                    }
                } catch (Exception e) {
                    // Not in activity context
                }
            }
            if (localContext == null) {
                log.warn("No LocalContext for tenant={} queue={} (version={}); cannot run execution tree", tenantId, effectiveQueue, requestedVersion);
                return "";
            }
            PipelineConfiguration config = localContext.getPipelineConfiguration();
            if (config == null || config.getPipelines() == null || config.getPipelines().isEmpty()) {
                log.warn("No pipelines in config for queue {}", effectiveQueue);
                return "";
            }
            String snapshotVersionId = requestedVersion != null ? requestedVersion : (config.getVersion() != null ? config.getVersion() : "");
            String runId = runLedger != null ? UUID.randomUUID().toString() : null;
            ExecutionConfigSnapshot snapshot = runId != null
                    ? ExecutionConfigSnapshot.of(tenantId, effectiveQueue, config, snapshotVersionId, runId)
                    : ExecutionConfigSnapshot.of(tenantId, effectiveQueue, config, snapshotVersionId);
            Map<String, Object> inputValues = new LinkedHashMap<>();
            for (com.olo.input.model.InputItem item : workflowInput.getInputs()) {
                if (item != null && item.getName() != null) {
                    inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
                }
            }
            Map<String, Object> tenantConfigMap = TenantConfigRegistry.getInstance().get(tenantId).getConfigMap();

            long ledgerStartTime = 0L;
            if (runLedger != null && runId != null) {
                LedgerContext.setRunId(runId);
                String pluginVersionsJson = buildPluginVersionsJson(tenantId, config);
                ledgerStartTime = System.currentTimeMillis();
                runLedger.runStarted(runId, tenantId, effectiveQueue, snapshotVersionId, snapshotVersionId, pluginVersionsJson, workflowInputJson, ledgerStartTime);
            }

            String runResult = null;
            String runStatus = "FAILED";
            Throwable runFailure = null;
            try {
                runResult = ExecutionEngine.run(snapshot, inputValues, pluginExecutor(tenantId), tenantConfigMap);
                runStatus = "SUCCESS";
                return runResult != null ? runResult : "";
            } catch (IllegalArgumentException e) {
                log.warn("Execution engine validation failed: {}", e.getMessage());
                runFailure = e;
                return "";
            } catch (Throwable t) {
                runFailure = t;
                throw t;
            } finally {
                if (runLedger != null) {
                    String runIdForEnd = LedgerContext.getRunId();
                    if (runIdForEnd == null) runIdForEnd = runId;
                    if (runIdForEnd != null) {
                        long endTime = System.currentTimeMillis();
                        Long durationMs = ledgerStartTime > 0 ? (endTime - ledgerStartTime) : null;
                        String errMsg = runFailure != null ? (runFailure.getMessage() != null ? runFailure.getMessage() : runFailure.getClass().getName()) : null;
                        String failureStage = runFailure != null ? runFailure.getClass().getSimpleName() : null;
                        runLedger.runEnded(runIdForEnd, endTime, runResult, runStatus, durationMs, errMsg, failureStage, null, null, "USD");
                    }
                    LedgerContext.clear();
                    RunLevelLedgerFeature.clearForRun();
                }
            }
        } finally {
            sessionCache.decrActiveWorkflows(tenantId);
        }
    }

    private static String buildPluginVersionsJson(String tenantId, PipelineConfiguration config) {
        Map<String, String> versions = new TreeMap<>();
        if (config != null && config.getPipelines() != null) {
            for (PipelineDefinition def : config.getPipelines().values()) {
                if (def == null || def.getExecutionTree() == null) continue;
                collectPluginRefs(def.getExecutionTree(), tenantId, versions);
            }
        }
        try {
            return MAPPER.writeValueAsString(versions);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void collectPluginRefs(ExecutionTreeNode node, String tenantId, Map<String, String> out) {
        if (node == null) return;
        if (node.getType() == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
            String ver = PluginRegistry.getInstance().getContractVersion(tenantId, node.getPluginRef());
            out.putIfAbsent(node.getPluginRef(), ver != null ? ver : "?");
        }
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) {
                collectPluginRefs(child, tenantId, out);
            }
        }
    }

    private PluginInvoker.PluginExecutor pluginExecutor(String tenantId) {
        return new PluginInvoker.PluginExecutor() {
            @Override
            public String execute(String pluginId, String inputsJson) {
                return OloKernelActivitiesImpl.this.executePlugin(tenantId, pluginId, inputsJson);
            }
            @Override
            public String toJson(Map<String, Object> map) {
                try {
                    return MAPPER.writeValueAsString(map != null ? map : Map.of());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize plugin inputs", e);
                }
            }
            @Override
            public Map<String, Object> fromJson(String json) {
                try {
                    return MAPPER.readValue(json != null ? json : "{}", MAP_TYPE);
                } catch (Exception e) {
                    log.warn("Failed to parse plugin outputs", e);
                    return Map.of();
                }
            }
        };
    }
}
