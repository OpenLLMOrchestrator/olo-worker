package com.olo.executiontree.outputcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.olo.executiontree.inputcontract.ParameterDef;

import java.util.List;
import java.util.Objects;

/**
 * Contract for the pipeline result: defines the final output shape to the user.
 * Parameters list the output fields (name, type). Execution variables are mapped
 * to these parameters via {@link ResultMapping}.
 */
public final class OutputContract {

    private final List<ParameterDef> parameters;

    @JsonCreator
    public OutputContract(@JsonProperty("parameters") List<ParameterDef> parameters) {
        this.parameters = parameters != null ? List.copyOf(parameters) : List.of();
    }

    /** Output parameter definitions (name, type) – the final result shape. */
    public List<ParameterDef> getParameters() {
        return parameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutputContract that = (OutputContract) o;
        return Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameters);
    }
}
