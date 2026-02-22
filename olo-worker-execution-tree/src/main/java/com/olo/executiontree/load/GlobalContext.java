package com.olo.executiontree.load;

import com.olo.executiontree.config.PipelineConfiguration;

import java.util.Objects;

/**
 * Read-only context for a queue: the pipeline configuration loaded for that queue.
 * Stored in the global context map at bootstrap.
 */
public final class GlobalContext {

    private final String queueName;
    private final PipelineConfiguration configuration;

    public GlobalContext(String queueName, PipelineConfiguration configuration) {
        this.queueName = Objects.requireNonNull(queueName, "queueName");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public String getQueueName() {
        return queueName;
    }

    /** Read-only pipeline configuration (execution tree, defaults, pipelines). */
    public PipelineConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GlobalContext that = (GlobalContext) o;
        return Objects.equals(queueName, that.queueName) && Objects.equals(configuration, that.configuration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(queueName, configuration);
    }
}
