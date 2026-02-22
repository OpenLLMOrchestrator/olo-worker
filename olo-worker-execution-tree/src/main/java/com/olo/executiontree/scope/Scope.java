package com.olo.executiontree.scope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Objects;

/** Scope: plugins and features available to the execution tree. */
public final class Scope {

    private final List<PluginDef> plugins;
    private final List<FeatureDef> features;

    @JsonCreator
    public Scope(
            @JsonProperty("plugins") List<PluginDef> plugins,
            @JsonProperty("features") @JsonDeserialize(using = FeatureDefListDeserializer.class) List<FeatureDef> features) {
        this.plugins = plugins != null ? List.copyOf(plugins) : List.of();
        this.features = features != null ? List.copyOf(features) : List.of();
    }

    public List<PluginDef> getPlugins() {
        return plugins;
    }

    public List<FeatureDef> getFeatures() {
        return features;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Scope scope = (Scope) o;
        return Objects.equals(plugins, scope.plugins) && Objects.equals(features, scope.features);
    }

    @Override
    public int hashCode() {
        return Objects.hash(plugins, features);
    }
}
