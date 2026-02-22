package com.olo.executiontree.tree;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Maps a plugin parameter to a variable (for input/output mappings). */
public final class ParameterMapping {

    private final String pluginParameter;
    private final String variable;

    @JsonCreator
    public ParameterMapping(
            @JsonProperty("pluginParameter") String pluginParameter,
            @JsonProperty("variable") String variable) {
        this.pluginParameter = pluginParameter;
        this.variable = variable;
    }

    public String getPluginParameter() {
        return pluginParameter;
    }

    public String getVariable() {
        return variable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterMapping that = (ParameterMapping) o;
        return Objects.equals(pluginParameter, that.pluginParameter) && Objects.equals(variable, that.variable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginParameter, variable);
    }
}
