package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Temporal engine defaults: target, namespace, taskQueuePrefix. */
public final class TemporalDefaults {

    private final String target;
    private final String namespace;
    private final String taskQueuePrefix;

    @JsonCreator
    public TemporalDefaults(
            @JsonProperty("target") String target,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("taskQueuePrefix") String taskQueuePrefix) {
        this.target = target;
        this.namespace = namespace;
        this.taskQueuePrefix = taskQueuePrefix;
    }

    public String getTarget() {
        return target;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getTaskQueuePrefix() {
        return taskQueuePrefix;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TemporalDefaults that = (TemporalDefaults) o;
        return Objects.equals(target, that.target) && Objects.equals(namespace, that.namespace)
                && Objects.equals(taskQueuePrefix, that.taskQueuePrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(target, namespace, taskQueuePrefix);
    }
}
