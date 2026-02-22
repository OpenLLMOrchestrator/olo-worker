package com.olo.worker.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.OloSessionCache;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executioncontext.LocalContext;
import com.olo.input.model.WorkflowInput;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.PluginRegistry;
import com.olo.worker.engine.ExecutionEngine;
import com.olo.worker.engine.PluginInvoker;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
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
    public String runExecutionTree(String queueName, String workflowInputJson) {
        WorkflowInput workflowInput;
        try {
            workflowInput = WorkflowInput.fromJson(workflowInputJson);
        } catch (Exception e) {
            log.warn("Invalid workflow input JSON for runExecutionTree", e);
            return "";
        }
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
        LocalContext localContext = LocalContext.forQueue(effectiveQueue);
        if (localContext == null) {
            try {
                String taskQueue = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
                if (taskQueue != null) {
                    localContext = LocalContext.forQueue(taskQueue);
                    if (localContext != null) {
                        effectiveQueue = taskQueue;
                    }
                }
            } catch (Exception e) {
                // Not in activity context
            }
        }
        if (localContext == null) {
            log.warn("No LocalContext for queue {}; cannot run execution tree", effectiveQueue);
            return "";
        }
        PipelineConfiguration config = localContext.getPipelineConfiguration();
        if (config == null || config.getPipelines() == null || config.getPipelines().isEmpty()) {
            log.warn("No pipelines in config for queue {}", effectiveQueue);
            return "";
        }
        String entryPipelineName = config.getPipelines().keySet().iterator().next();
        Map<String, Object> inputValues = new LinkedHashMap<>();
        for (com.olo.input.model.InputItem item : workflowInput.getInputs()) {
            if (item != null && item.getName() != null) {
                inputValues.put(item.getName(), item.getValue() != null ? item.getValue() : "");
            }
        }
        try {
            return ExecutionEngine.run(config, entryPipelineName, effectiveQueue, inputValues, pluginExecutor());
        } catch (IllegalArgumentException e) {
            log.warn("Execution engine validation failed: {}", e.getMessage());
            return "";
        }
    }

    private PluginInvoker.PluginExecutor pluginExecutor() {
        return new PluginInvoker.PluginExecutor() {
            @Override
            public String execute(String pluginId, String inputsJson) {
                return OloKernelActivitiesImpl.this.executePlugin(pluginId, inputsJson);
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
