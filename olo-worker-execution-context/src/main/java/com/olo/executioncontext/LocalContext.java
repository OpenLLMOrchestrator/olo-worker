package com.olo.executioncontext;

import com.olo.executiontree.ExecutionTreeConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.load.GlobalConfigurationContext;
import com.olo.executiontree.load.GlobalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Local (per-workflow) execution context holding a deep copy of the pipeline configuration
 * (execution tree) for the queue where the workflow was created.
 * <p>
 * Create via {@link #forQueue(String)} when a new workflow starts: obtains the configuration
 * for that queue from the global context and stores a deep copy so the workflow can use it
 * without being affected by later global changes.
 */
public final class LocalContext {

    private static final Logger log = LoggerFactory.getLogger(LocalContext.class);

    private final String queueName;
    private final PipelineConfiguration pipelineConfiguration;

    private LocalContext(String queueName, PipelineConfiguration pipelineConfiguration) {
        this.queueName = Objects.requireNonNull(queueName, "queueName");
        this.pipelineConfiguration = Objects.requireNonNull(pipelineConfiguration, "pipelineConfiguration");
    }

    /**
     * Creates a local context for the given queue by taking a deep copy of the pipeline
     * configuration from the global context. Call this when a new workflow starts.
     *
     * @param queueName task queue name (e.g. olo-chat-queue-oolama or olo-chat-queue-oolama-debug)
     * @return local context with a deep copy of the execution tree for that queue, or null if no config is loaded for the queue
     */
    public static LocalContext forQueue(String queueName) {
        Objects.requireNonNull(queueName, "queueName");
        GlobalContext global = GlobalConfigurationContext.get(queueName);
        if (global == null) {
            log.warn("No global pipeline configuration for queue={}; cannot create LocalContext", queueName);
            return null;
        }
        PipelineConfiguration source = global.getConfiguration();
        PipelineConfiguration deepCopy = deepCopy(source);
        log.debug("Created LocalContext for queue={} with deep copy of pipeline configuration", queueName);
        return new LocalContext(queueName, deepCopy);
    }

    /**
     * Returns a deep copy of the given pipeline configuration (via JSON round-trip).
     */
    public static PipelineConfiguration deepCopy(PipelineConfiguration config) {
        if (config == null) return null;
        String json = ExecutionTreeConfig.toJson(config);
        return ExecutionTreeConfig.fromJson(json);
    }

    /** Queue name this context was created for. */
    public String getQueueName() {
        return queueName;
    }

    /** Deep copy of the pipeline configuration (execution tree) for this queue. Safe to use and mutate locally. */
    public PipelineConfiguration getPipelineConfiguration() {
        return pipelineConfiguration;
    }
}
