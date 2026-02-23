package com.olo.features;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Context passed to feature hooks when a tree node is about to run (pre) or has just run (post).
 * Provides node identity and type so the feature can decide what to do; optional attributes
 * for extensibility. Includes tenant id and tenant-specific config (e.g. restrictions, 3rd party params).
 * For PLUGIN nodes, queueName and pluginId are set for metrics. Execution outcome is set for post phases:
 * use {@link #withExecutionSucceeded(boolean)} when calling postSuccess/postError/finally so features
 * can use {@link #isExecutionSucceeded()} (success = no exception thrown).
 */
public final class NodeExecutionContext {

    private final String nodeId;
    private final String type;
    private final String nodeType;
    private final Map<String, Object> attributes;
    private final String tenantId;
    private final Map<String, Object> tenantConfigMap;
    private final String queueName;
    private final String pluginId;
    /** True = postSuccess path, false = postError path, null = pre or unknown. Set via {@link #withExecutionSucceeded(boolean)}. */
    private final Boolean executionSucceeded;

    public NodeExecutionContext(String nodeId, String type, String nodeType, Map<String, Object> attributes,
                                String tenantId, Map<String, Object> tenantConfigMap) {
        this(nodeId, type, nodeType, attributes, tenantId, tenantConfigMap, null, null, null);
    }

    /**
     * Full constructor including optional pipeline/plugin and execution-outcome context.
     *
     * @param queueName          pipeline / task queue name (for plugin metrics); null if not set
     * @param pluginId           plugin ref for PLUGIN nodes (e.g. GPT4_EXECUTOR); null otherwise
     * @param executionSucceeded true if postSuccess path, false if postError path, null if pre or unknown
     */
    public NodeExecutionContext(String nodeId, String type, String nodeType, Map<String, Object> attributes,
                                String tenantId, Map<String, Object> tenantConfigMap,
                                String queueName, String pluginId, Boolean executionSucceeded) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.type = type;
        this.nodeType = nodeType;
        this.attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        this.tenantId = tenantId != null ? tenantId : "";
        this.tenantConfigMap = tenantConfigMap != null ? Map.copyOf(tenantConfigMap) : Map.of();
        this.queueName = queueName != null ? queueName : "";
        this.pluginId = pluginId != null ? pluginId : "";
        this.executionSucceeded = executionSucceeded;
    }

    public NodeExecutionContext(String nodeId, String type, String nodeType, Map<String, Object> attributes) {
        this(nodeId, type, nodeType, attributes, null, null);
    }

    public NodeExecutionContext(String nodeId, String type, String nodeType) {
        this(nodeId, type, nodeType, null);
    }

    /** Returns a new context with the given execution outcome (for postSuccess/postError/finally). */
    public NodeExecutionContext withExecutionSucceeded(boolean succeeded) {
        return new NodeExecutionContext(nodeId, type, nodeType, attributes, tenantId, tenantConfigMap,
                queueName, pluginId, succeeded);
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

    /** Tenant id for this execution (for tenant-specific restrictions or config). */
    public String getTenantId() {
        return tenantId;
    }

    /** Tenant-specific config (e.g. restrictions, 3rd party params). Unmodifiable. */
    public Map<String, Object> getTenantConfigMap() {
        return tenantConfigMap;
    }

    /** Pipeline / task queue name (for plugin metrics). Empty if not set. */
    public String getQueueName() {
        return queueName;
    }

    /** Plugin ref for PLUGIN nodes (e.g. GPT4_EXECUTOR). Empty if not a plugin node or not set. */
    public String getPluginId() {
        return pluginId;
    }

    /** True if this context is for the postSuccess path, false for postError, null if pre or unknown. */
    public Boolean getExecutionSucceeded() {
        return executionSucceeded;
    }

    /** Convenience: true only when {@link #getExecutionSucceeded()} is Boolean.TRUE. */
    public boolean isExecutionSucceeded() {
        return Boolean.TRUE.equals(executionSucceeded);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object v = attributes.get(key);
        return (v != null && type.isInstance(v)) ? (T) v : null;
    }
}
