package com.olo.executiontree;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.io.UncheckedIOException;

/**
 * Serialization and deserialization of the pipeline configuration (root) and pipeline definitions.
 * JSON excludes null values when serializing.
 */
public final class ExecutionTreeConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ExecutionTreeConfig() {
    }

    /**
     * Deserializes the root pipeline configuration from a JSON string.
     *
     * @param json the JSON string (e.g. from file or API)
     * @return the parsed {@link PipelineConfiguration}
     * @throws UncheckedIOException on parse failure
     */
    private static final TypeReference<PipelineConfiguration> CONFIG_TYPE = new TypeReference<>() {};

    public static PipelineConfiguration fromJson(String json) {
        try {
            return MAPPER.readValue(json, CONFIG_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serializes the pipeline configuration to a JSON string (nulls excluded).
     *
     * @param config the root configuration to serialize
     * @return JSON string
     * @throws UncheckedIOException on serialization failure
     */
    public static String toJson(PipelineConfiguration config) {
        try {
            return MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Serializes the pipeline configuration to a pretty-printed JSON string (nulls excluded).
     */
    public static String toJsonPretty(PipelineConfiguration config) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Serializes a single pipeline definition to JSON (e.g. for embedding or API).
     */
    public static String toJson(PipelineDefinition pipeline) {
        try {
            return MAPPER.writeValueAsString(pipeline);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Deserializes a single pipeline definition from JSON.
     */
    public static PipelineDefinition pipelineFromJson(String json) {
        try {
            return MAPPER.readValue(json, PipelineDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns a new pipeline configuration where every execution tree node has a non-blank id;
     * generates a UUID for any node whose id is null or blank. Use before persisting to Redis/DB
     * so each node has a unique nodeId.
     */
    public static PipelineConfiguration ensureUniqueNodeIds(PipelineConfiguration config) {
        if (config == null) return null;
        Map<String, PipelineDefinition> pipelines = config.getPipelines();
        if (pipelines == null || pipelines.isEmpty()) return config;
        Map<String, PipelineDefinition> normalized = new HashMap<>();
        for (Map.Entry<String, PipelineDefinition> e : pipelines.entrySet()) {
            PipelineDefinition def = e.getValue();
            if (def == null) {
                normalized.put(e.getKey(), null);
                continue;
            }
            ExecutionTreeNode tree = def.getExecutionTree();
            ExecutionTreeNode ensuredTree = ExecutionTreeNode.withEnsuredUniqueId(tree);
            normalized.put(e.getKey(), def.withExecutionTree(ensuredTree));
        }
        return config.withPipelines(normalized);
    }

    /**
     * Returns a new pipeline configuration where every execution tree node has a new UUID
     * (all existing ids are replaced). Use to refresh all node ids before writing to Redis/DB.
     */
    public static PipelineConfiguration refreshAllNodeIds(PipelineConfiguration config) {
        if (config == null) return null;
        Map<String, PipelineDefinition> pipelines = config.getPipelines();
        if (pipelines == null || pipelines.isEmpty()) return config;
        Map<String, PipelineDefinition> refreshed = new HashMap<>();
        for (Map.Entry<String, PipelineDefinition> e : pipelines.entrySet()) {
            PipelineDefinition def = e.getValue();
            if (def == null) {
                refreshed.put(e.getKey(), null);
                continue;
            }
            ExecutionTreeNode tree = def.getExecutionTree();
            ExecutionTreeNode refreshedTree = ExecutionTreeNode.withRefreshedIds(tree);
            refreshed.put(e.getKey(), def.withExecutionTree(refreshedTree));
        }
        return config.withPipelines(refreshed);
    }
}
