package com.olo.executiontree.inputcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Definition of a single parameter (name, type, optional required flag). */
public final class ParameterDef {

    private final String name;
    private final String type;
    private final Boolean required;

    @JsonCreator
    public ParameterDef(
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("required") Boolean required) {
        this.name = name;
        this.type = type;
        this.required = required != null ? required : false;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterDef that = (ParameterDef) o;
        return Objects.equals(name, that.name) && Objects.equals(type, that.type) && Objects.equals(required, that.required);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, required);
    }
}
