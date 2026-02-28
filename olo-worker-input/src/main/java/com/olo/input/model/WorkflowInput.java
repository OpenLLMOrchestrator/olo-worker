package com.olo.input.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Root workflow input: version, inputs, context, routing, metadata.
 * Supports JSON serialization/deserialization and a fluent builder.
 * Can be deserialized from a JSON object or from a string containing JSON (e.g. Temporal payload).
 * {@link WorkflowInputDeserializer} is registered so both object and string payloads are handled.
 */
public final class WorkflowInput {

    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        SimpleModule module = new SimpleModule();
        module.addDeserializer(WorkflowInput.class, new WorkflowInputDeserializer());
        mapper.registerModule(module);
        return mapper;
    }

    private final String version;
    private final List<InputItem> inputs;
    private final Context context;
    private final Routing routing;
    private final Metadata metadata;

    /** Used when the payload is a JSON object. */
    @JsonCreator
    public WorkflowInput(
            @JsonProperty("version") String version,
            @JsonProperty("inputs") List<InputItem> inputs,
            @JsonProperty("context") Context context,
            @JsonProperty("routing") Routing routing,
            @JsonProperty("metadata") Metadata metadata) {
        this.version = version;
        this.inputs = inputs != null ? List.copyOf(inputs) : List.of();
        this.context = context;
        this.routing = routing;
        this.metadata = metadata;
    }

    /**
     * Delegating creator: used when the payload is a JSON string (e.g. Temporal workflow argument).
     * Enables any worker's ObjectMapper to deserialize without registering a custom deserializer.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WorkflowInput fromJsonString(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json.trim(), WorkflowInput.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String getVersion() {
        return version;
    }

    public List<InputItem> getInputs() {
        return inputs;
    }

    public Context getContext() {
        return context;
    }

    public Routing getRouting() {
        return routing;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    /**
     * Deserializes from JSON string. Throws {@link UncheckedIOException} on failure.
     */
    public static WorkflowInput fromJson(String json) {
        try {
            return MAPPER.readValue(json, WorkflowInput.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serializes this instance to a JSON string. Throws {@link UncheckedIOException} on failure.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Serializes this instance to a pretty-printed JSON string.
     */
    public String toJsonPretty() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkflowInput that = (WorkflowInput) o;
        return Objects.equals(version, that.version)
                && Objects.equals(inputs, that.inputs)
                && Objects.equals(context, that.context)
                && Objects.equals(routing, that.routing)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, inputs, context, routing, metadata);
    }

    public static final class Builder {
        private String version;
        private final List<InputItem> inputs = new ArrayList<>();
        private Context context;
        private Routing routing;
        private Metadata metadata;

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder addInput(InputItem input) {
            this.inputs.add(Objects.requireNonNull(input, "input"));
            return this;
        }

        public Builder inputs(List<InputItem> inputs) {
            this.inputs.clear();
            if (inputs != null) {
                this.inputs.addAll(inputs);
            }
            return this;
        }

        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        public Builder routing(Routing routing) {
            this.routing = routing;
            return this;
        }

        public Builder metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public WorkflowInput build() {
            return new WorkflowInput(
                    version,
                    List.copyOf(inputs),
                    context,
                    routing,
                    metadata
            );
        }
    }
}
