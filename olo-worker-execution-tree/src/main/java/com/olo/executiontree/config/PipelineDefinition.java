package com.olo.executiontree.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.olo.executiontree.inputcontract.InputContract;
import com.olo.executiontree.outputcontract.OutputContract;
import com.olo.executiontree.outputcontract.ResultMapping;
import com.olo.executiontree.scope.Scope;
import com.olo.executiontree.defaults.ActivityDefaultTimeouts;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.variableregistry.VariableRegistryEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One pipeline in the configuration: name, workflowId, inputContract,
 * variableRegistry, scope, executionTree, outputContract, resultMapping.
 * Version is at root ({@link PipelineConfiguration}). The output contract
 * defines the final result to the user; resultMapping maps execution variables to it.
 */
public final class PipelineDefinition {

    private final String name;
    private final String workflowId;
    private final InputContract inputContract;
    private final List<VariableRegistryEntry> variableRegistry;
    private final Scope scope;
    private final ExecutionTreeNode executionTree;
    private final OutputContract outputContract;
    private final List<ResultMapping> resultMapping;
    private final ExecutionType executionType;
    private final Map<String, ActivityDefaultTimeouts> resolvedNodeTimeouts;

    @JsonCreator
    public PipelineDefinition(
            @JsonProperty("name") String name,
            @JsonProperty("workflowId") String workflowId,
            @JsonProperty("inputContract") InputContract inputContract,
            @JsonProperty("variableRegistry") List<VariableRegistryEntry> variableRegistry,
            @JsonProperty("scope") Scope scope,
            @JsonProperty("executionTree") ExecutionTreeNode executionTree,
            @JsonProperty("outputContract") OutputContract outputContract,
            @JsonProperty("resultMapping") List<ResultMapping> resultMapping,
            @JsonProperty("executionType") ExecutionType executionType,
            @JsonProperty("resolvedNodeTimeouts") Map<String, ActivityDefaultTimeouts> resolvedNodeTimeouts) {
        this.name = name;
        this.workflowId = workflowId;
        this.inputContract = inputContract;
        this.variableRegistry = variableRegistry != null ? List.copyOf(variableRegistry) : List.of();
        this.scope = scope;
        this.executionTree = executionTree;
        this.outputContract = outputContract;
        this.resultMapping = resultMapping != null ? List.copyOf(resultMapping) : List.of();
        this.executionType = executionType != null ? executionType : ExecutionType.SYNC;
        this.resolvedNodeTimeouts = resolvedNodeTimeouts != null ? Collections.unmodifiableMap(resolvedNodeTimeouts) : null;
    }

    /** Constructor for JSON deserialization when resolvedNodeTimeouts is not present. */
    public PipelineDefinition(
            String name,
            String workflowId,
            InputContract inputContract,
            List<VariableRegistryEntry> variableRegistry,
            Scope scope,
            ExecutionTreeNode executionTree,
            OutputContract outputContract,
            List<ResultMapping> resultMapping,
            ExecutionType executionType) {
        this(name, workflowId, inputContract, variableRegistry, scope, executionTree, outputContract, resultMapping, executionType, null);
    }

    /** Pipeline name (used as key in the pipelines map). */
    public String getName() {
        return name;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public InputContract getInputContract() {
        return inputContract;
    }

    public List<VariableRegistryEntry> getVariableRegistry() {
        return variableRegistry;
    }

    public Scope getScope() {
        return scope;
    }

    public ExecutionTreeNode getExecutionTree() {
        return executionTree;
    }

    /** Output contract: final result shape to the user. */
    public OutputContract getOutputContract() {
        return outputContract;
    }

    /** Maps execution variables (e.g. OUT) to output contract parameters (final result). */
    public List<ResultMapping> getResultMapping() {
        return resultMapping;
    }

    /** SYNC (default) or ASYNC. When ASYNC, all nodes except JOIN run in a worker thread. */
    public ExecutionType getExecutionType() {
        return executionType;
    }

    /** Resolved activity timeouts per node id (current → parent → global), resolved at bootstrap. Null until resolved. */
    public Map<String, ActivityDefaultTimeouts> getResolvedNodeTimeouts() {
        return resolvedNodeTimeouts;
    }

    /** Returns a new pipeline definition with the given execution tree (e.g. after ensuring unique node ids). */
    public PipelineDefinition withExecutionTree(ExecutionTreeNode executionTree) {
        return new PipelineDefinition(
                name, workflowId, inputContract, variableRegistry, scope,
                executionTree, outputContract, resultMapping, executionType, resolvedNodeTimeouts);
    }

    /** Returns a new pipeline definition with resolved node timeouts (set at bootstrap). */
    public PipelineDefinition withResolvedNodeTimeouts(Map<String, ActivityDefaultTimeouts> resolvedNodeTimeouts) {
        return new PipelineDefinition(
                name, workflowId, inputContract, variableRegistry, scope,
                executionTree, outputContract, resultMapping, executionType, resolvedNodeTimeouts);
    }

    /** Returns a new pipeline definition with the given execution type (e.g. ASYNC when tree contains FORK). */
    public PipelineDefinition withExecutionType(ExecutionType executionType) {
        return new PipelineDefinition(
                name, workflowId, inputContract, variableRegistry, scope,
                executionTree, outputContract, resultMapping,
                executionType != null ? executionType : ExecutionType.SYNC,
                resolvedNodeTimeouts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineDefinition that = (PipelineDefinition) o;
        return Objects.equals(name, that.name) && Objects.equals(workflowId, that.workflowId)
                && Objects.equals(inputContract, that.inputContract)
                && Objects.equals(variableRegistry, that.variableRegistry)
                && Objects.equals(scope, that.scope)
                && Objects.equals(executionTree, that.executionTree)
                && Objects.equals(outputContract, that.outputContract)
                && Objects.equals(resultMapping, that.resultMapping)
                && executionType == that.executionType
                && Objects.equals(resolvedNodeTimeouts, that.resolvedNodeTimeouts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, workflowId, inputContract, variableRegistry, scope, executionTree,
                outputContract, resultMapping, executionType, resolvedNodeTimeouts);
    }
}
