package com.olo.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Semantic execution event for chat UI and observability.
 * Expose as readable steps (e.g. "Planner decided to call: searchDocuments") instead of raw logs.
 *
 * @see ExecutionEventSink
 * @see com.olo.ledger.ExecutionEventsFeature
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ExecutionEvent {

    /** Event type for UI representation (e.g. planner.started, tool.completed). */
    private final String eventType;
    /** Human-readable label (e.g. "Planner started", "Tool executing: searchDocuments"). */
    private final String label;
    /** Optional payload (reason, tool name, snippet). Keep small and readable. */
    private final Map<String, Object> payload;
    /** Epoch millis. */
    private final long timestampMillis;
    /** Node id when applicable. */
    private final String nodeId;

    public ExecutionEvent(String eventType, String label, Map<String, Object> payload, long timestampMillis, String nodeId) {
        this.eventType = eventType != null ? eventType : "";
        this.label = label != null ? label : "";
        this.payload = payload;
        this.timestampMillis = timestampMillis;
        this.nodeId = nodeId;
    }

    public String getEventType() { return eventType; }
    public String getLabel() { return label; }
    public Map<String, Object> getPayload() { return payload; }
    public long getTimestampMillis() { return timestampMillis; }
    public String getNodeId() { return nodeId; }

    /** Predefined event types for chat UI. */
    public static final class EventType {
        public static final String PLANNER_STARTED = "planner.started";
        public static final String PLANNER_COMPLETED = "planner.completed";
        public static final String TOOL_STARTED = "tool.started";
        public static final String TOOL_COMPLETED = "tool.completed";
        public static final String MODEL_STARTED = "model.started";
        public static final String MODEL_TOKEN = "model.token";
        public static final String MODEL_COMPLETED = "model.completed";
        public static final String HUMAN_REQUIRED = "human.required";
        public static final String WORKFLOW_STARTED = "workflow.started";
        public static final String WORKFLOW_COMPLETED = "workflow.completed";
        public static final String WORKFLOW_FAILED = "workflow.failed";
    }

    /** UI representation hints (emoji + short text) for event types. */
    public static String uiLabelForEventType(String eventType) {
        if (eventType == null) return "";
        return switch (eventType) {
            case EventType.PLANNER_STARTED -> "⏳ Planner started";
            case EventType.PLANNER_COMPLETED -> "🧠 Planner output";
            case EventType.TOOL_STARTED -> "🔧 Tool executing";
            case EventType.TOOL_COMPLETED -> "✅ Tool result";
            case EventType.MODEL_STARTED -> "🤖 Model call";
            case EventType.MODEL_TOKEN -> "streaming text";
            case EventType.MODEL_COMPLETED -> "🤖 Model result";
            case EventType.HUMAN_REQUIRED -> "✋ Approval required";
            case EventType.WORKFLOW_STARTED -> "workflow started";
            case EventType.WORKFLOW_COMPLETED -> "🎉 Done";
            case EventType.WORKFLOW_FAILED -> "❌ Error";
            default -> eventType;
        };
    }
}
