package com.olo.ledger;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.features.FinallyCall;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PluginExecutionResult;
import com.olo.features.PreNodeCall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits semantic execution events to {@link ExecutionEventSink} for chat UI display.
 * Expose readable steps (e.g. "Planner decided to call: searchDocuments") instead of raw logs.
 *
 * @see ExecutionEvent
 * @see ExecutionEvent.EventType
 */
@OloFeature(name = "execution-events", phase = FeaturePhase.PRE_FINALLY, applicableNodeTypes = { "*" })
public final class ExecutionEventsFeature implements PreNodeCall, FinallyCall {

    private final ExecutionEventSink sink;

    public ExecutionEventsFeature(ExecutionEventSink sink) {
        this.sink = sink;
    }

    @Override
    public void before(NodeExecutionContext context) {
        if (sink == null) return;
        String runId = LedgerContext.getRunId();
        if (runId == null || runId.isBlank()) return;
        String type = context.getType();
        if (type == null || type.isBlank()) return;
        long now = System.currentTimeMillis();
        String startedType = startedEventType(type, context.getPluginId());
        if (startedType == null) return;
        String nodeId = context.getNodeId();
        String label = semanticLabel(startedType, context, null);
        sink.emit(runId, new ExecutionEvent(startedType, label, payload(context.getPluginId(), context.getQueueName(), null), now, nodeId));
    }

    @Override
    public void afterFinally(NodeExecutionContext context, Object nodeResult) {
        if (sink == null) return;
        String runId = LedgerContext.getRunId();
        if (runId == null || runId.isBlank()) return;
        String type = context.getType();
        if (type == null || type.isBlank()) return;
        long now = System.currentTimeMillis();
        boolean success = context.getExecutionSucceeded() != null && context.isExecutionSucceeded();
        String completedType = completedEventType(type, context.getPluginId(), success);
        if (completedType == null) return;
        String nodeId = context.getNodeId();
        String label = semanticLabel(completedType, context, nodeResult);
        Map<String, Object> payload = payload(context.getPluginId(), context.getQueueName(), nodeResult);
        String message = humanReadableMessage(completedType, nodeResult);
        if (message != null && !message.isBlank()) {
            payload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
            payload.put("message", message);
        }
        if (!success && context.getAttributes() != null && context.getAttributes().containsKey("error")) {
            payload = payload != null ? payload : new LinkedHashMap<>();
            Object err = context.getAttributes().get("error");
            payload.put("error", err != null ? err.toString() : "Unknown error");
        }
        sink.emit(runId, new ExecutionEvent(completedType, label, payload != null && !payload.isEmpty() ? payload : null, now, nodeId));
    }

    private static String startedEventType(String structuralType, String pluginId) {
        String upper = structuralType != null ? structuralType.toUpperCase() : "";
        if ("PLANNER".equals(upper)) return ExecutionEvent.EventType.PLANNER_STARTED;
        if ("PLUGIN".equals(upper)) return isModelPlugin(pluginId) ? ExecutionEvent.EventType.MODEL_STARTED : ExecutionEvent.EventType.TOOL_STARTED;
        return null;
    }

    private static String completedEventType(String structuralType, String pluginId, boolean success) {
        String upper = structuralType != null ? structuralType.toUpperCase() : "";
        if ("PLANNER".equals(upper)) return ExecutionEvent.EventType.PLANNER_COMPLETED;
        if ("PLUGIN".equals(upper)) return isModelPlugin(pluginId) ? ExecutionEvent.EventType.MODEL_COMPLETED : ExecutionEvent.EventType.TOOL_COMPLETED;
        return null;
    }

    private static boolean isModelPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return false;
        String lower = pluginId.toLowerCase();
        return lower.contains("model") || lower.contains("executor") || lower.contains("ollama")
                || lower.contains("gpt") || lower.contains("openai") || lower.contains("litellm");
    }

    private static String semanticLabel(String eventType, NodeExecutionContext context, Object nodeResult) {
        if (eventType == null) return "";
        String pluginId = context.getPluginId();
        String display = pluginId != null && !pluginId.isBlank() ? pluginId : context.getNodeId();
        if (ExecutionEvent.EventType.PLANNER_COMPLETED.equals(eventType) && nodeResult != null) {
            String msg = humanReadableMessage(eventType, nodeResult);
            if (msg != null && !msg.isBlank()) return msg;
        }
        return switch (eventType) {
            case ExecutionEvent.EventType.PLANNER_STARTED -> "Planner started";
            case ExecutionEvent.EventType.PLANNER_COMPLETED -> "Planner output";
            case ExecutionEvent.EventType.TOOL_STARTED -> "Tool executing: " + display;
            case ExecutionEvent.EventType.TOOL_COMPLETED -> "Tool result: " + display;
            case ExecutionEvent.EventType.MODEL_STARTED -> "Model call: " + display;
            case ExecutionEvent.EventType.MODEL_COMPLETED -> "Model result: " + display;
            default -> ExecutionEvent.uiLabelForEventType(eventType);
        };
    }

    /**
     * Build a human-readable message for the chat UI from the node result.
     * Planner: "Planner decided to run: X, then Y". Tool/Model: short snippet of response if available.
     */
    private static String humanReadableMessage(String eventType, Object nodeResult) {
        if (nodeResult == null) return null;
        if (nodeResult instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) nodeResult;
            Object planSummary = m.get("planSummary");
            if (planSummary != null && !planSummary.toString().isBlank()) return planSummary.toString();
            Object message = m.get("message");
            if (message != null && !message.toString().isBlank()) return message.toString();
        }
        if (nodeResult instanceof PluginExecutionResult) {
            Map<String, Object> out = ((PluginExecutionResult) nodeResult).getOutputs();
            if (out == null) return null;
            Object planSummary = out.get("planSummary");
            if (planSummary != null && !planSummary.toString().isBlank()) return planSummary.toString();
            Object message = out.get("message");
            if (message != null && !message.toString().isBlank()) return message.toString();
            Object responseText = out.get("responseText");
            if (responseText != null) {
                String s = responseText.toString().trim();
                if (!s.isBlank()) return snippet(s, 200);
            }
            Object content = out.get("content");
            if (content != null) {
                String s = content.toString().trim();
                if (!s.isBlank()) return snippet(s, 200);
            }
        }
        return null;
    }

    private static String snippet(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static Map<String, Object> payload(String pluginId, String queueName, Object nodeResult) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (pluginId != null && !pluginId.isBlank()) p.put("pluginId", pluginId);
        if (queueName != null && !queueName.isBlank()) p.put("queueName", queueName);
        return p.isEmpty() ? null : p;
    }
}
