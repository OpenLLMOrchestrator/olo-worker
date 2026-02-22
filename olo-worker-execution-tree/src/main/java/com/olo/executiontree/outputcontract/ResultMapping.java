package com.olo.executiontree.outputcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Maps an execution variable (e.g. OUT scope) to an output parameter in the
 * {@link OutputContract}. The final result to the user is built from these mappings.
 */
public final class ResultMapping {

    private final String variable;
    private final String outputParameter;

    @JsonCreator
    public ResultMapping(
            @JsonProperty("variable") String variable,
            @JsonProperty("outputParameter") String outputParameter) {
        this.variable = variable;
        this.outputParameter = outputParameter;
    }

    /** Variable name from execution (typically scope OUT). */
    public String getVariable() {
        return variable;
    }

    /** Output parameter name in the output contract (final result field to the user). */
    public String getOutputParameter() {
        return outputParameter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResultMapping that = (ResultMapping) o;
        return Objects.equals(variable, that.variable) && Objects.equals(outputParameter, that.outputParameter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variable, outputParameter);
    }
}
