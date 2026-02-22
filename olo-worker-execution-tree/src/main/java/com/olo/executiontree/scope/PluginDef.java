package com.olo.executiontree.scope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.olo.executiontree.inputcontract.ParameterDef;

import java.util.List;
import java.util.Objects;

/** Plugin definition in scope: id, displayName, contractType, contractVersion, inputParameters, outputParameters. */
public final class PluginDef {

    private final String id;
    private final String displayName;
    private final String contractType;
    private final String contractVersion;
    private final List<ParameterDef> inputParameters;
    private final List<ParameterDef> outputParameters;

    @JsonCreator
    public PluginDef(
            @JsonProperty("id") String id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("contractType") String contractType,
            @JsonProperty("contractVersion") String contractVersion,
            @JsonProperty("inputParameters") List<ParameterDef> inputParameters,
            @JsonProperty("outputParameters") List<ParameterDef> outputParameters) {
        this.id = id;
        this.displayName = displayName;
        this.contractType = contractType;
        this.contractVersion = contractVersion;
        this.inputParameters = inputParameters != null ? List.copyOf(inputParameters) : List.of();
        this.outputParameters = outputParameters != null ? List.copyOf(outputParameters) : List.of();
    }

    public String getId() {
        return id;
    }

    /** Human-readable name for UI (optional). */
    public String getDisplayName() {
        return displayName;
    }

    public String getContractType() {
        return contractType;
    }

    /** Contract version (e.g. 1.0) for compatibility checks; null = any. */
    public String getContractVersion() {
        return contractVersion;
    }

    public List<ParameterDef> getInputParameters() {
        return inputParameters;
    }

    public List<ParameterDef> getOutputParameters() {
        return outputParameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PluginDef that = (PluginDef) o;
        return Objects.equals(id, that.id) && Objects.equals(displayName, that.displayName)
                && Objects.equals(contractType, that.contractType)
                && Objects.equals(contractVersion, that.contractVersion)
                && Objects.equals(inputParameters, that.inputParameters)
                && Objects.equals(outputParameters, that.outputParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, contractType, contractVersion, inputParameters, outputParameters);
    }
}
