package com.olo.worker.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.OloSessionCache;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.load.GlobalConfigurationContext;
import com.olo.executiontree.scope.FeatureDef;
import com.olo.executiontree.scope.Scope;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.features.FeatureAttachmentResolver;
import com.olo.features.FeatureRegistry;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PostNodeCall;
import com.olo.features.PreNodeCall;
import com.olo.features.ResolvedPrePost;
import com.olo.input.model.WorkflowInput;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.PluginRegistry;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public OloKernelActivitiesImpl(OloSessionCache sessionCache) {
        this.sessionCache = sessionCache;
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
        ModelExecutorPlugin plugin = PluginRegistry.getInstance().getModelExecutor(pluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("No model-executor plugin registered for id: " + pluginId);
        }
        Map<String, Object> inputs;
        try {
            inputs = MAPPER.readValue(inputsJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid inputs JSON for plugin " + pluginId + ": " + e.getMessage(), e);
        }
        try {
            Map<String, Object> outputs = plugin.execute(inputs);
            return MAPPER.writeValueAsString(outputs != null ? outputs : Map.of());
        } catch (Exception e) {
            log.error("Plugin execution failed: pluginId={}", pluginId, e);
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
    public String getChatResponseWithFeatures(String queueName, String pluginId, String prompt) {
        String effectiveQueue = queueName;
        if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
            try {
                String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null && taskQueue.endsWith("-debug")) {
                    effectiveQueue = taskQueue;
                }
            } catch (Exception e) {
                // Not in activity context (e.g. unit test) or context unavailable
            }
        }
        if (effectiveQueue == null || !effectiveQueue.endsWith("-debug")) {
            return getChatResponse(pluginId, prompt);
        }
        com.olo.executiontree.load.GlobalContext globalCtx = GlobalConfigurationContext.get(effectiveQueue);
        if (globalCtx == null) {
            log.debug("No pipeline config for queue {}, skipping pre/post features", effectiveQueue);
            return getChatResponse(pluginId, prompt);
        }
        PipelineConfiguration pipelineConfig = globalCtx.getConfiguration();
        ExecutionTreeNode pluginNode = findPluginNodeByRef(pipelineConfig, pluginId);
        if (pluginNode == null) {
            log.debug("No PLUGIN node with pluginRef={} in pipeline {}, skipping pre/post", pluginId, effectiveQueue);
            return getChatResponse(pluginId, prompt);
        }
        List<String> scopeFeatureNames = getScopeFeatureNames(pipelineConfig);
        FeatureRegistry registry = FeatureRegistry.getInstance();
        ResolvedPrePost resolved = FeatureAttachmentResolver.resolve(pluginNode, effectiveQueue, scopeFeatureNames, registry);
        NodeExecutionContext context = new NodeExecutionContext(
                pluginNode.getId(),
                pluginNode.getType(),
                pluginNode.getNodeType()
        );
        // Pre
        for (String name : resolved.getPreExecution()) {
            FeatureRegistry.FeatureEntry e = registry.get(name);
            if (e == null) continue;
            Object inst = e.getInstance();
            if (inst instanceof PreNodeCall) {
                ((PreNodeCall) inst).before(context);
            }
        }
        String nodeResult = null;
        try {
            nodeResult = getChatResponse(pluginId, prompt);
            return nodeResult != null ? nodeResult : "";
        } finally {
            // Post (run even if getChatResponse threw, so debug post still logs)
            for (String name : resolved.getPostExecution()) {
                FeatureRegistry.FeatureEntry e = registry.get(name);
                if (e == null) continue;
                Object inst = e.getInstance();
                if (inst instanceof PostNodeCall) {
                    try {
                        ((PostNodeCall) inst).after(context, nodeResult);
                    } catch (Throwable t) {
                        log.warn("Post feature {} failed", name, t);
                    }
                }
            }
        }
    }

    private static List<String> getScopeFeatureNames(PipelineConfiguration pipelineConfig) {
        List<String> names = new ArrayList<>();
        if (pipelineConfig == null || pipelineConfig.getPipelines() == null) return names;
        for (PipelineDefinition def : pipelineConfig.getPipelines().values()) {
            Scope scope = def != null ? def.getScope() : null;
            if (scope == null || scope.getFeatures() == null) continue;
            for (FeatureDef f : scope.getFeatures()) {
                if (f != null && f.getId() != null && !f.getId().isBlank()) {
                    names.add(f.getId().trim());
                }
            }
            break; // use first pipeline's scope
        }
        return names;
    }

    private static ExecutionTreeNode findPluginNodeByRef(PipelineConfiguration pipelineConfig, String pluginRef) {
        if (pipelineConfig == null || pipelineConfig.getPipelines() == null || pluginRef == null) return null;
        for (PipelineDefinition def : pipelineConfig.getPipelines().values()) {
            ExecutionTreeNode root = def != null ? def.getExecutionTree() : null;
            ExecutionTreeNode found = findPluginNodeByRefRec(root, pluginRef);
            if (found != null) return found;
        }
        return null;
    }

    private static ExecutionTreeNode findPluginNodeByRefRec(ExecutionTreeNode node, String pluginRef) {
        if (node == null) return null;
        if ("PLUGIN".equals(node.getType()) && pluginRef.equals(node.getPluginRef())) {
            return node;
        }
        for (ExecutionTreeNode child : node.getChildren()) {
            ExecutionTreeNode found = findPluginNodeByRefRec(child, pluginRef);
            if (found != null) return found;
        }
        return null;
    }
}
