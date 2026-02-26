package com.olo.worker.activity;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.input.model.WorkflowInput;
import com.olo.ledger.LedgerContext;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.ledger.RunLedger;
import com.olo.internal.features.InternalFeatures;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.node.PipelineFeatureContextImpl;
import com.olo.plugin.PluginExecutor;
import com.olo.plugin.PluginExecutorFactory;
import com.olo.worker.engine.ExecutionEngine;
import com.olo.worker.engine.ExecutionPlanBuilder;
import com.olo.worker.engine.node.NodeExecutor;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.ResultMapper;
import com.olo.worker.engine.VariableEngine;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final PluginExecutorFactory pluginExecutorFactory;
    private final DynamicNodeBuilder dynamicNodeBuilder;
    private final NodeFeatureEnricher nodeFeatureEnricher;

    public OloKernelActivitiesImpl(OloSessionCache sessionCache, List<String> allowedTenantIds, RunLedger runLedger,
                                   PluginExecutorFactory pluginExecutorFactory,
                                   DynamicNodeBuilder dynamicNodeBuilder,
                                   NodeFeatureEnricher nodeFeatureEnricher) {
        this.sessionCache = sessionCache;
        this.allowedTenantIds = allowedTenantIds != null ? Set.copyOf(allowedTenantIds) : Set.of();
        this.runLedger = runLedger;
        this.pluginExecutorFactory = pluginExecutorFactory != null ? pluginExecutorFactory : (tenantId, cache) -> { throw new IllegalStateException("PluginExecutorFactory not set; worker must be started with bootstrap context"); };
        this.dynamicNodeBuilder = dynamicNodeBuilder;
        this.nodeFeatureEnricher = nodeFeatureEnricher != null ? nodeFeatureEnricher : (n, c) -> n;
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
        return executePlugin(OloConfig.normalizeTenantId(null), pluginId, inputsJson);
    }

    /**
     * Executes a plugin for the given tenant. When {@code nodeId} and {@code nodeInstanceCache} are non-null,
     * uses per-node instance (one per tree node; same node in a loop reuses the same instance).
     * Uses the injected {@link PluginExecutorFactory} (contract only; no dependency on PluginRegistry).
     */
    private String executePlugin(String tenantId, String pluginId, String inputsJson) {
        return executePlugin(tenantId, pluginId, inputsJson, null, null);
    }

    private String executePlugin(String tenantId, String pluginId, String inputsJson,
                                  String nodeId, java.util.Map<String, ?> nodeInstanceCache) {
        PluginExecutor executor = pluginExecutorFactory.create(tenantId, nodeInstanceCache);
        try {
            return executor.execute(pluginId, inputsJson, nodeId);
        } catch (Exception e) {
            log.error("Plugin execution failed: tenant={} pluginId={}", tenantId, pluginId, e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Plugin execution failed: " + pluginId + " - " + e.getMessage(), e);
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
    public String getExecutionPlan(String queueName, String workflowInputJson) {
        WorkflowInput workflowInput;
        try {
            workflowInput = WorkflowInput.fromJson(workflowInputJson);
        } catch (Exception e) {
            log.warn("Invalid workflow input JSON for getExecutionPlan", e);
            return "{\"linear\":false}";
        }
        String tenantId = OloConfig.normalizeTenantId(
                workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        String effectiveQueue = queueName;
        if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
            try {
                String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null && taskQueue.endsWith("-debug")) effectiveQueue = taskQueue;
            } catch (Exception ignored) { }
        }
        String requestedVersion = workflowInput.getRouting() != null ? workflowInput.getRouting().getConfigVersion() : null;
        if (requestedVersion != null && requestedVersion.isBlank()) requestedVersion = null;
        else if (requestedVersion != null) requestedVersion = requestedVersion.trim();
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
        if (localContext == null) {
            log.warn("No LocalContext for getExecutionPlan");
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
            log.warn("Failed to serialize execution plan", e);
            return "{\"linear\":false}";
        }
    }

    @Override
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
            log.warn("applyResultMapping failed", e);
            return "";
        }
    }

    @Override
    public String executeNode(String activityType, String planJson, String nodeId, String variableMapJson,
                              String queueName, String workflowInputJson, String dynamicStepsJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planJson", planJson);
        payload.put("nodeId", nodeId);
        payload.put("variableMapJson", variableMapJson);
        payload.put("queueName", queueName != null ? queueName : "");
        payload.put("workflowInputJson", workflowInputJson);
        if (dynamicStepsJson != null && !dynamicStepsJson.isBlank()) {
            payload.put("dynamicStepsJson", dynamicStepsJson);
        }
        try {
            return executeNode(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build executeNode payload", e);
        }
    }

    /**
     * Executes a single node (payload JSON). Used by {@link #executeNode(String, String, String, String, String, String, String)}.
     */
    private String executeNode(String payloadJson) {
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
        String tenantId = OloConfig.normalizeTenantId(
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid configJson: " + e.getMessage(), e);
        }
        PipelineDefinition pipeline = config.getPipelines() != null ? config.getPipelines().get(pipelineName) : null;
        if (pipeline == null || pipeline.getExecutionTree() == null) {
            throw new IllegalArgumentException("Pipeline or execution tree not found");
        }
        // Set LedgerContext.runId so NodeLedgerFeature (and other node features) do not skip (per-node activity path).
        RunLedger effectiveRunLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
        String runId = UUID.randomUUID().toString();
        LedgerContext.setRunId(runId);
        long ledgerStartTime = System.currentTimeMillis();
        String pluginVersionsJson = buildPluginVersionsJson(tenantId, config);
        effectiveRunLedger.runStarted(runId, tenantId, queueName, queueName, queueName, pluginVersionsJson, workflowInputJson, ledgerStartTime);

        String runResult = null;
        String runStatus = "SUCCESS";
        try {
            ExecutionTreeNode node = ExecutionTreeNode.findNodeById(pipeline.getExecutionTree(), nodeId);
            Map<String, Object> variableMap;
            try {
                variableMap = MAPPER.readValue(variableMapJson, MAP_TYPE);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid variableMapJson: " + e.getMessage(), e);
            }
            VariableEngine variableEngine = VariableEngine.fromVariableMap(pipeline, variableMap);
            java.util.Map<String, Object> nodeInstanceCache = new LinkedHashMap<>();
            PluginExecutor executor = pluginExecutorFactory.create(tenantId, nodeInstanceCache);
            PluginInvoker pluginInvoker = new PluginInvoker(executor);
            NodeExecutor nodeExecutor = new NodeExecutor(
                    pluginInvoker, config, pipeline.getExecutionType(), null, tenantId,
                    TenantConfigRegistry.getInstance().get(tenantId).getConfigMap(), runId, dynamicNodeBuilder, nodeFeatureEnricher);

            if (node == null && dynamicStepsJson != null && !dynamicStepsJson.isBlank()) {
                ExecutionTreeNode stepNode = resolveDynamicStep(nodeId, dynamicStepsJson);
                if (stepNode != null) {
                    stepNode = nodeFeatureEnricher.enrich(stepNode, new PipelineFeatureContextImpl(pipeline.getScope(), queueName));
                    nodeExecutor.executeSingleNode(stepNode, pipeline, variableEngine, queueName);
                    try {
                        runResult = MAPPER.writeValueAsString(variableEngine.getExportMap());
                        return runResult;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to serialize variable map", e);
                    }
                }
            }
            if (node == null) {
                throw new IllegalArgumentException("Node not found: " + nodeId);
            }
            if (node.getType() == NodeType.PLANNER) {
                List<ExecutionTreeNode> steps = nodeExecutor.executePlannerOnly(node, pipeline, variableEngine, queueName);
                List<Map<String, Object>> dynamicSteps = new ArrayList<>();
                for (ExecutionTreeNode step : steps) {
                    dynamicSteps.add(dynamicStepFromNode(step));
                }
                Map<String, Object> plannerResult = new LinkedHashMap<>();
                try {
                    plannerResult.put("variableMapJson", MAPPER.writeValueAsString(variableEngine.getExportMap()));
                    plannerResult.put("dynamicSteps", dynamicSteps);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize planner result", e);
                }
                try {
                    runResult = MAPPER.writeValueAsString(plannerResult);
                    return runResult;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize planner result JSON", e);
                }
            }
            nodeExecutor.executeSingleNode(node, pipeline, variableEngine, queueName);
            try {
                runResult = MAPPER.writeValueAsString(variableEngine.getExportMap());
                return runResult;
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize variable map", e);
            }
        } catch (Throwable t) {
            runStatus = "FAILED";
            throw t;
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

    private static Map<String, Object> dynamicStepFromNode(ExecutionTreeNode n) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", n.getId());
        String activityType = NodeType.PLUGIN.name();
        if (n.getPluginRef() != null && !n.getPluginRef().isBlank()) {
            activityType = NodeType.PLUGIN.name() + ":" + n.getPluginRef();
        }
        step.put("activityType", activityType);
        step.put("pluginRef", n.getPluginRef());
        step.put("displayName", n.getDisplayName());
        if (n.getFeatures() != null && !n.getFeatures().isEmpty()) {
            step.put("features", new ArrayList<>(n.getFeatures()));
        }
        List<Map<String, String>> inputMappings = new ArrayList<>();
        if (n.getInputMappings() != null) {
            for (ParameterMapping m : n.getInputMappings()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("pluginParameter", m.getPluginParameter());
                entry.put("variable", m.getVariable());
                inputMappings.add(entry);
            }
        }
        step.put("inputMappings", inputMappings);
        List<Map<String, String>> outputMappings = new ArrayList<>();
        if (n.getOutputMappings() != null) {
            for (ParameterMapping m : n.getOutputMappings()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("pluginParameter", m.getPluginParameter());
                entry.put("variable", m.getVariable());
                outputMappings.add(entry);
            }
        }
        step.put("outputMappings", outputMappings);
        return step;
    }

    private static ExecutionTreeNode resolveDynamicStep(String nodeId, String dynamicStepsJson) {
        List<Map<String, Object>> steps;
        try {
            steps = MAPPER.readValue(dynamicStepsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
        if (steps == null) return null;
        for (Map<String, Object> step : steps) {
            Object id = step.get("nodeId");
            if (id != null && id.toString().equals(nodeId)) {
                return nodeFromDynamicStep(step);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ExecutionTreeNode nodeFromDynamicStep(Map<String, Object> step) {
        String id = step.get("nodeId") != null ? step.get("nodeId").toString() : UUID.randomUUID().toString();
        String displayName = step.get("displayName") != null ? step.get("displayName").toString() : "step";
        String pluginRef = step.get("pluginRef") != null ? step.get("pluginRef").toString() : null;
        List<ParameterMapping> inputMappings = new ArrayList<>();
        Object in = step.get("inputMappings");
        if (in instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map) {
                    Map<String, String> m = (Map<String, String>) o;
                    String pp = m != null ? m.get("pluginParameter") : null;
                    String v = m != null ? m.get("variable") : null;
                    if (pp != null && v != null) {
                        inputMappings.add(new ParameterMapping(pp, v));
                    }
                }
            }
        }
        List<ParameterMapping> outputMappings = new ArrayList<>();
        Object out = step.get("outputMappings");
        if (out instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map) {
                    Map<String, String> m = (Map<String, String>) o;
                    String pp = m != null ? m.get("pluginParameter") : null;
                    String v = m != null ? m.get("variable") : null;
                    if (pp != null && v != null) {
                        outputMappings.add(new ParameterMapping(pp, v));
                    }
                }
            }
        }
        List<String> features = new ArrayList<>();
        Object feat = step.get("features");
        if (feat instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    features.add(o.toString().trim());
                }
            }
        }
        List<String> empty = List.of();
        return new ExecutionTreeNode(
                id, displayName, NodeType.PLUGIN, List.of(), "PLUGIN", pluginRef,
                inputMappings, outputMappings,
                features.isEmpty() ? empty : features, empty, empty, empty, empty, empty, empty, empty,
                Map.of(), null, null, null);
    }

    @Override
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
        String effectiveQueueForLog = queueName != null ? queueName : "";
        log.info("RunExecutionTree activity started | invoked from workflow | queue={} | tenantId={}",
                effectiveQueueForLog, tenantId);
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
            PipelineDefinition pipeline = config.getPipelines().values().iterator().next();
            ExecutionTreeNode rootNode = pipeline.getExecutionTree();
            String pipelineName = pipeline.getName();
            String executionMode = pipeline.getExecutionType() != null ? pipeline.getExecutionType().name() : "SYNC";
            String rootNodeId = rootNode != null ? rootNode.getId() : null;
            String rootNodeType = rootNode != null && rootNode.getType() != null ? rootNode.getType().name() : null;
            String rootNodeDisplayName = rootNode != null ? rootNode.getDisplayName() : null;
            String transactionId = workflowInput.getRouting() != null ? workflowInput.getRouting().getTransactionId() : null;
            log.info("OloKernel runExecutionTree | transactionId={} | pipelineName={} | executionMode={} | queue={} | tenantId={} | rootNodeId={} | rootNodeType={} | rootNodeDisplayName={} | configVersion={}",
                    transactionId, pipelineName, executionMode, effectiveQueue, tenantId, rootNodeId, rootNodeType, rootNodeDisplayName, snapshotVersionId);
            log.info("Execution tree entry | transactionId={} | pipelineName={} | rootNodeId={} | rootNodeType={}",
                    transactionId, pipelineName, rootNodeId, rootNodeType);
            // Use no-op ledger when none provided so LedgerContext.runId is always set and node features don't skip.
            RunLedger effectiveRunLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
            if (runLedger == null) {
                log.info("Run ledger was null; using no-op ledger so runId is set and node ledger features can run (run/node records will not be persisted).");
            }
            // One unique runId per execution (never reused across runs).
            String runId = UUID.randomUUID().toString();
            ExecutionConfigSnapshot snapshot = ExecutionConfigSnapshot.of(tenantId, effectiveQueue, config, snapshotVersionId, runId);
            Map<String, Object> inputValues = new LinkedHashMap<>();
            for (com.olo.input.model.InputItem item : workflowInput.getInputs()) {
                if (item != null && item.getName() != null) {
                    inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
                }
            }
            Map<String, Object> tenantConfigMap = TenantConfigRegistry.getInstance().get(tenantId).getConfigMap();

            // Per-node plugin instance cache: one instance per tree node; same node in a loop reuses the same instance.
            java.util.Map<String, Object> nodeInstanceCache = new LinkedHashMap<>();

            long ledgerStartTime = 0L;
            LedgerContext.setRunId(runId);
            String pluginVersionsJson = buildPluginVersionsJson(tenantId, config);
            ledgerStartTime = System.currentTimeMillis();
            effectiveRunLedger.runStarted(runId, tenantId, effectiveQueue, snapshotVersionId, snapshotVersionId, pluginVersionsJson, workflowInputJson, ledgerStartTime);
            log.info("Ledger runStarted invoked | runId={} | pipeline={} (ledger-node will record each node when LedgerContext has runId)", runId, effectiveQueue);

            String runResult = null;
            String runStatus = "FAILED";
            Throwable runFailure = null;
            try {
                PluginExecutor executor = pluginExecutorFactory.create(tenantId, nodeInstanceCache);
                runResult = ExecutionEngine.run(snapshot, inputValues, executor, tenantConfigMap, dynamicNodeBuilder, nodeFeatureEnricher);
                runStatus = "SUCCESS";
                int resultLen = runResult != null ? runResult.length() : 0;
                String resultSnippet = runResult != null && runResult.length() > 400
                        ? runResult.substring(0, 400) + "...[truncated]"
                        : (runResult != null ? runResult : "");
                log.info("Execution tree exit | transactionId={} | status={} | resultLength={} | resultSnippet={}",
                        transactionId, runStatus, resultLen, resultSnippet);
                log.info("Activity exit | runExecutionTree | status={} | resultLength={}", runStatus, resultLen);
                return runResult != null ? runResult : "";
            } catch (IllegalArgumentException e) {
                log.warn("Execution engine validation failed: {}", e.getMessage());
                runFailure = e;
                log.info("Execution tree exit | transactionId={} | status=VALIDATION_FAILED | resultLength=0", transactionId);
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
            out.putIfAbsent(node.getPluginRef(), "?");
        }
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) {
                collectPluginRefs(child, tenantId, out);
            }
        }
    }
}
