package com.olo.executiontree.tree;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Node in the execution tree. Can be a container (e.g. SEQUENCE with children)
 * or a plugin node (PLUGIN with pluginRef, inputMappings, outputMappings).
 * Each node can have explicit pre/post feature lists and featureRequired/featureNotRequired overrides.
 * Post phases: postExecution (legacy, merged into the three below by resolver), postSuccessExecution,
 * postErrorExecution, finallyExecution. The executor runs postSuccess on success, postError on exception, then finally always.
 */
public final class ExecutionTreeNode {

    private final String id;
    private final String displayName;
    private final NodeType type;
    private final List<ExecutionTreeNode> children;
    private final String nodeType;
    private final String pluginRef;
    private final List<ParameterMapping> inputMappings;
    private final List<ParameterMapping> outputMappings;
    private final List<String> features;
    private final List<String> preExecution;
    private final List<String> postExecution;
    private final List<String> postSuccessExecution;
    private final List<String> postErrorExecution;
    private final List<String> finallyExecution;
    private final List<String> featureRequired;
    private final List<String> featureNotRequired;
    private final Map<String, Object> params;
    private final Integer scheduleToStartSeconds;
    private final Integer startToCloseSeconds;
    private final Integer scheduleToCloseSeconds;

    @JsonCreator
    public ExecutionTreeNode(
            @JsonProperty("id") String id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("type") NodeType type,
            @JsonProperty("children") List<ExecutionTreeNode> children,
            @JsonProperty("nodeType") String nodeType,
            @JsonProperty("pluginRef") String pluginRef,
            @JsonProperty("inputMappings") List<ParameterMapping> inputMappings,
            @JsonProperty("outputMappings") List<ParameterMapping> outputMappings,
            @JsonProperty("features") List<String> features,
            @JsonProperty("preExecution") List<String> preExecution,
            @JsonProperty("postExecution") List<String> postExecution,
            @JsonProperty("postSuccessExecution") List<String> postSuccessExecution,
            @JsonProperty("postErrorExecution") List<String> postErrorExecution,
            @JsonProperty("finallyExecution") List<String> finallyExecution,
            @JsonProperty("featureRequired") List<String> featureRequired,
            @JsonProperty("featureNotRequired") List<String> featureNotRequired,
            @JsonProperty("params") Map<String, Object> params,
            @JsonProperty("scheduleToStartSeconds") Integer scheduleToStartSeconds,
            @JsonProperty("startToCloseSeconds") Integer startToCloseSeconds,
            @JsonProperty("scheduleToCloseSeconds") Integer scheduleToCloseSeconds) {
        this.id = id;
        this.displayName = displayName;
        this.type = type != null ? type : NodeType.UNKNOWN;
        this.children = children != null ? List.copyOf(children) : List.of();
        this.nodeType = nodeType;
        this.pluginRef = pluginRef;
        this.inputMappings = inputMappings != null ? List.copyOf(inputMappings) : List.of();
        this.outputMappings = outputMappings != null ? List.copyOf(outputMappings) : List.of();
        this.features = features != null ? List.copyOf(features) : List.of();
        this.preExecution = preExecution != null ? List.copyOf(preExecution) : List.of();
        this.postExecution = postExecution != null ? List.copyOf(postExecution) : List.of();
        this.postSuccessExecution = postSuccessExecution != null ? List.copyOf(postSuccessExecution) : List.of();
        this.postErrorExecution = postErrorExecution != null ? List.copyOf(postErrorExecution) : List.of();
        this.finallyExecution = finallyExecution != null ? List.copyOf(finallyExecution) : List.of();
        this.featureRequired = featureRequired != null ? List.copyOf(featureRequired) : List.of();
        this.featureNotRequired = featureNotRequired != null ? List.copyOf(featureNotRequired) : List.of();
        this.params = params != null ? Map.copyOf(params) : Map.of();
        this.scheduleToStartSeconds = scheduleToStartSeconds;
        this.startToCloseSeconds = startToCloseSeconds;
        this.scheduleToCloseSeconds = scheduleToCloseSeconds;
    }

    public String getId() {
        return id;
    }

    /**
     * Finds a node by id in the tree (DFS). Returns null if not found.
     */
    public static ExecutionTreeNode findNodeById(ExecutionTreeNode root, String nodeId) {
        if (root == null || nodeId == null || nodeId.isBlank()) return null;
        if (nodeId.equals(root.id)) return root;
        if (root.children != null) {
            for (ExecutionTreeNode child : root.children) {
                ExecutionTreeNode found = findNodeById(child, nodeId);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Human-readable name for UI (optional). */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns a new tree where every node has a UUID as id. Keeps existing id only if it is already a valid UUID;
     * otherwise (null, blank, or non-UUID like "modelNode" or "root") assigns a new UUID. Use before persisting to Redis/DB.
     */
    public static ExecutionTreeNode withEnsuredUniqueId(ExecutionTreeNode node) {
        if (node == null) return null;
        String nodeId = isUuid(node.id) ? node.id : UUID.randomUUID().toString();
        List<ExecutionTreeNode> newChildren = node.children.stream()
                .map(ExecutionTreeNode::withEnsuredUniqueId)
                .toList();
        return new ExecutionTreeNode(
                nodeId,
                node.displayName,
                node.type,
                newChildren,
                node.nodeType,
                node.pluginRef,
                node.inputMappings,
                node.outputMappings,
                node.features,
                node.preExecution,
                node.postExecution,
                node.postSuccessExecution,
                node.postErrorExecution,
                node.finallyExecution,
                node.featureRequired,
                node.featureNotRequired,
                node.params,
                node.scheduleToStartSeconds,
                node.startToCloseSeconds,
                node.scheduleToCloseSeconds
        );
    }

    /**
     * Returns a new tree where every node has a new UUID (existing ids are replaced).
     * Use to refresh all node ids before writing to Redis/DB.
     */
    public static ExecutionTreeNode withRefreshedIds(ExecutionTreeNode node) {
        if (node == null) return null;
        String nodeId = UUID.randomUUID().toString();
        List<ExecutionTreeNode> newChildren = node.children.stream()
                .map(ExecutionTreeNode::withRefreshedIds)
                .toList();
        return new ExecutionTreeNode(
                nodeId,
                node.displayName,
                node.type,
                newChildren,
                node.nodeType,
                node.pluginRef,
                node.inputMappings,
                node.outputMappings,
                node.features,
                node.preExecution,
                node.postExecution,
                node.postSuccessExecution,
                node.postErrorExecution,
                node.finallyExecution,
                node.featureRequired,
                node.featureNotRequired,
                node.params,
                node.scheduleToStartSeconds,
                node.startToCloseSeconds,
                node.scheduleToCloseSeconds
        );
    }

    private static boolean isUuid(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Structural node type (SEQUENCE, PLUGIN, IF, etc.). Never null. */
    public NodeType getType() {
        return type;
    }

    public List<ExecutionTreeNode> getChildren() {
        return children;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getPluginRef() {
        return pluginRef;
    }

    public List<ParameterMapping> getInputMappings() {
        return inputMappings;
    }

    public List<ParameterMapping> getOutputMappings() {
        return outputMappings;
    }

    /** Feature names (shorthand; resolver merges into pre/post by phase). */
    public List<String> getFeatures() {
        return features;
    }

    /** Feature names to run before this node (explicit list from config). */
    public List<String> getPreExecution() {
        return preExecution;
    }

    /** Feature names to run after this node (legacy; resolver merges into postSuccess/postError/finally by phase). */
    public List<String> getPostExecution() {
        return postExecution;
    }

    /** Feature names to run after this node completes successfully. */
    public List<String> getPostSuccessExecution() {
        return postSuccessExecution;
    }

    /** Feature names to run after this node throws an exception. */
    public List<String> getPostErrorExecution() {
        return postErrorExecution;
    }

    /** Feature names to run after this node (success or error). */
    public List<String> getFinallyExecution() {
        return finallyExecution;
    }

    /** Features that must be attached to this node (resolver adds by phase). */
    public List<String> getFeatureRequired() {
        return featureRequired;
    }

    /** Features to exclude from this node (e.g. opt out of debug). */
    public List<String> getFeatureNotRequired() {
        return featureNotRequired;
    }

    /** Type-specific parameters (e.g. conditionVariable for IF, mergeStrategy for JOIN, collectionVariable for ITERATOR). Unmodifiable. */
    public Map<String, Object> getParams() {
        return params;
    }

    /** Optional: schedule-to-start timeout in seconds for this node. Resolved at bootstrap: current → parent → global default. */
    public Integer getScheduleToStartSeconds() {
        return scheduleToStartSeconds;
    }

    /** Optional: start-to-close timeout in seconds for this node. Resolved at bootstrap: current → parent → global default. */
    public Integer getStartToCloseSeconds() {
        return startToCloseSeconds;
    }

    /** Optional: schedule-to-close timeout in seconds for this node. Resolved at bootstrap: current → parent → global default. */
    public Integer getScheduleToCloseSeconds() {
        return scheduleToCloseSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionTreeNode that = (ExecutionTreeNode) o;
        return Objects.equals(id, that.id) && type == that.type
                && Objects.equals(children, that.children) && Objects.equals(nodeType, that.nodeType)
                && Objects.equals(pluginRef, that.pluginRef)
                && Objects.equals(inputMappings, that.inputMappings)
                && Objects.equals(outputMappings, that.outputMappings)
                && Objects.equals(features, that.features)
                && Objects.equals(preExecution, that.preExecution)
                && Objects.equals(postExecution, that.postExecution)
                && Objects.equals(postSuccessExecution, that.postSuccessExecution)
                && Objects.equals(postErrorExecution, that.postErrorExecution)
                && Objects.equals(finallyExecution, that.finallyExecution)
                && Objects.equals(featureRequired, that.featureRequired)
                && Objects.equals(featureNotRequired, that.featureNotRequired)
                && Objects.equals(params, that.params)
                && Objects.equals(scheduleToStartSeconds, that.scheduleToStartSeconds)
                && Objects.equals(startToCloseSeconds, that.startToCloseSeconds)
                && Objects.equals(scheduleToCloseSeconds, that.scheduleToCloseSeconds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, type, children, nodeType, pluginRef, inputMappings, outputMappings,
                features, preExecution, postExecution, postSuccessExecution, postErrorExecution, finallyExecution,
                featureRequired, featureNotRequired, params, scheduleToStartSeconds, startToCloseSeconds, scheduleToCloseSeconds);
    }
}
