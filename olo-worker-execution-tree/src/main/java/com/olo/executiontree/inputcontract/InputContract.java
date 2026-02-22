package com.olo.executiontree.inputcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/** Contract for workflow input: strict mode and parameter definitions. */
public final class InputContract {

    private final boolean strict;
    private final List<ParameterDef> parameters;

    @JsonCreator
    public InputContract(
            @JsonProperty("strict") Boolean strict,
            @JsonProperty("parameters") List<ParameterDef> parameters) {
        this.strict = strict != null ? strict : false;
        this.parameters = parameters != null ? List.copyOf(parameters) : List.of();
    }

    public boolean isStrict() {
        return strict;
    }

    public List<ParameterDef> getParameters() {
        return parameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InputContract that = (InputContract) o;
        return strict == that.strict && Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(strict, parameters);
    }
}
