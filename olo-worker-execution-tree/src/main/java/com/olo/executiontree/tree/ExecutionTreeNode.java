package com.olo.executiontree.tree;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Node in the execution tree. Can be a container (e.g. SEQUENCE with children)
 * or a plugin node (PLUGIN with pluginRef, inputMappings, outputMappings).
 * Each node can have explicit preExecution/postExecution lists and featureRequired/featureNotRequired overrides.
 */
public final class ExecutionTreeNode {

    private final String id;
    private final String displayName;
    private final String type;
    private final List<ExecutionTreeNode> children;
    private final String nodeType;
    private final String pluginRef;
    private final List<ParameterMapping> inputMappings;
    private final List<ParameterMapping> outputMappings;
    private final List<String> features;
    private final List<String> preExecution;
    private final List<String> postExecution;
    private final List<String> featureRequired;
    private final List<String> featureNotRequired;

    @JsonCreator
    public ExecutionTreeNode(
            @JsonProperty("id") String id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("type") String type,
            @JsonProperty("children") List<ExecutionTreeNode> children,
            @JsonProperty("nodeType") String nodeType,
            @JsonProperty("pluginRef") String pluginRef,
            @JsonProperty("inputMappings") List<ParameterMapping> inputMappings,
            @JsonProperty("outputMappings") List<ParameterMapping> outputMappings,
            @JsonProperty("features") List<String> features,
            @JsonProperty("preExecution") List<String> preExecution,
            @JsonProperty("postExecution") List<String> postExecution,
            @JsonProperty("featureRequired") List<String> featureRequired,
            @JsonProperty("featureNotRequired") List<String> featureNotRequired) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.children = children != null ? List.copyOf(children) : List.of();
        this.nodeType = nodeType;
        this.pluginRef = pluginRef;
        this.inputMappings = inputMappings != null ? List.copyOf(inputMappings) : List.of();
        this.outputMappings = outputMappings != null ? List.copyOf(outputMappings) : List.of();
        this.features = features != null ? List.copyOf(features) : List.of();
        this.preExecution = preExecution != null ? List.copyOf(preExecution) : List.of();
        this.postExecution = postExecution != null ? List.copyOf(postExecution) : List.of();
        this.featureRequired = featureRequired != null ? List.copyOf(featureRequired) : List.of();
        this.featureNotRequired = featureNotRequired != null ? List.copyOf(featureNotRequired) : List.of();
    }

    public String getId() {
        return id;
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
                node.featureRequired,
                node.featureNotRequired
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
                node.featureRequired,
                node.featureNotRequired
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

    public String getType() {
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

    /** Feature names to run after this node (explicit list from config). */
    public List<String> getPostExecution() {
        return postExecution;
    }

    /** Features that must be attached to this node (resolver adds by phase). */
    public List<String> getFeatureRequired() {
        return featureRequired;
    }

    /** Features to exclude from this node (e.g. opt out of debug). */
    public List<String> getFeatureNotRequired() {
        return featureNotRequired;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionTreeNode that = (ExecutionTreeNode) o;
        return Objects.equals(id, that.id) && Objects.equals(type, that.type)
                && Objects.equals(children, that.children) && Objects.equals(nodeType, that.nodeType)
                && Objects.equals(pluginRef, that.pluginRef)
                && Objects.equals(inputMappings, that.inputMappings)
                && Objects.equals(outputMappings, that.outputMappings)
                && Objects.equals(features, that.features)
                && Objects.equals(preExecution, that.preExecution)
                && Objects.equals(postExecution, that.postExecution)
                && Objects.equals(featureRequired, that.featureRequired)
                && Objects.equals(featureNotRequired, that.featureNotRequired);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, type, children, nodeType, pluginRef, inputMappings, outputMappings,
                features, preExecution, postExecution, featureRequired, featureNotRequired);
    }
}
