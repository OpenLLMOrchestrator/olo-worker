package com.olo.executiontree.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.olo.executiontree.defaults.ExecutionDefaults;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Root pipeline configuration: version, executionDefaults, pipelines stored by name (map),
 * and root-level plugin and feature restrictions.
 */
public final class PipelineConfiguration {

    private final String version;
    private final ExecutionDefaults executionDefaults;
    private final List<String> pluginRestrictions;
    private final List<String> featureRestrictions;
    private final Map<String, PipelineDefinition> pipelines;

    @JsonCreator
    public PipelineConfiguration(
            @JsonProperty("version") String version,
            @JsonProperty("executionDefaults") ExecutionDefaults executionDefaults,
            @JsonProperty("pluginRestrictions") List<String> pluginRestrictions,
            @JsonProperty("featureRestrictions") List<String> featureRestrictions,
            @JsonProperty("pipelines") Map<String, PipelineDefinition> pipelines) {
        this.version = version;
        this.executionDefaults = executionDefaults;
        this.pluginRestrictions = pluginRestrictions != null ? List.copyOf(pluginRestrictions) : List.of();
        this.featureRestrictions = featureRestrictions != null ? List.copyOf(featureRestrictions) : List.of();
        this.pipelines = pipelines != null ? Collections.unmodifiableMap(Map.copyOf(pipelines)) : Map.of();
    }

    /** Configuration version (at root level). */
    public String getVersion() {
        return version;
    }

    /** Execution defaults (engine, temporal, activity). */
    public ExecutionDefaults getExecutionDefaults() {
        return executionDefaults;
    }

    /** Allowed plugin IDs at root level (empty = no restriction). */
    public List<String> getPluginRestrictions() {
        return pluginRestrictions;
    }

    /** Allowed feature IDs at root level (empty = no restriction). */
    public List<String> getFeatureRestrictions() {
        return featureRestrictions;
    }

    /** Pipeline definitions by name (map key = pipeline name). */
    public Map<String, PipelineDefinition> getPipelines() {
        return pipelines;
    }

    /** Returns a new configuration with the given pipelines map (e.g. after normalizing node ids). */
    public PipelineConfiguration withPipelines(Map<String, PipelineDefinition> pipelines) {
        return new PipelineConfiguration(
                version, executionDefaults, pluginRestrictions, featureRestrictions,
                pipelines != null ? Collections.unmodifiableMap(Map.copyOf(pipelines)) : Map.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PipelineConfiguration that = (PipelineConfiguration) o;
        return Objects.equals(version, that.version)
                && Objects.equals(executionDefaults, that.executionDefaults)
                && Objects.equals(pluginRestrictions, that.pluginRestrictions)
                && Objects.equals(featureRestrictions, that.featureRestrictions)
                && Objects.equals(pipelines, that.pipelines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, executionDefaults, pluginRestrictions, featureRestrictions, pipelines);
    }
}
