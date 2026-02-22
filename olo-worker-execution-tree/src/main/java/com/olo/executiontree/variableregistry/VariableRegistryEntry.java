package com.olo.executiontree.variableregistry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Entry in the variable registry: name, type, and scope (IN, INTERNAL, OUT). */
public final class VariableRegistryEntry {

    private final String name;
    private final String type;
    private final VariableScope scope;

    @JsonCreator
    public VariableRegistryEntry(
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("scope") VariableScope scope) {
        this.name = name;
        this.type = type;
        this.scope = scope;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public VariableScope getScope() {
        return scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VariableRegistryEntry that = (VariableRegistryEntry) o;
        return Objects.equals(name, that.name) && Objects.equals(type, that.type) && scope == that.scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, scope);
    }
}
