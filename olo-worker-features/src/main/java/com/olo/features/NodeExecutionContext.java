package com.olo.features;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Context passed to feature hooks when a tree node is about to run (pre) or has just run (post).
 * Provides node identity and type so the feature can decide what to do; optional attributes
 * for extensibility.
 */
public final class NodeExecutionContext {

    private final String nodeId;
    private final String type;
    private final String nodeType;
    private final Map<String, Object> attributes;

    public NodeExecutionContext(String nodeId, String type, String nodeType, Map<String, Object> attributes) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.type = type;
        this.nodeType = nodeType;
        this.attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    public NodeExecutionContext(String nodeId, String type, String nodeType) {
        this(nodeId, type, nodeType, null);
    }

    public String getNodeId() {
        return nodeId;
    }

    /** Node structural type (e.g. SEQUENCE, PLUGIN, GROUP, IF). */
    public String getType() {
        return type;
    }

    /** Plugin/category type (e.g. MODAL.xyz, PLANNER.abc). May be null. */
    public String getNodeType() {
        return nodeType;
    }

    /** Extra context (e.g. variables, parent id). Unmodifiable. */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object v = attributes.get(key);
        return (v != null && type.isInstance(v)) ? (T) v : null;
    }
}
