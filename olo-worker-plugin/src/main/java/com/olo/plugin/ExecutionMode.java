package com.olo.plugin;

/**
 * Execution mode for plugins invoked by the execution tree.
 *
 * <p>Determines how the runtime should execute a plugin:
 * inside the workflow thread (deterministic), as a local activity,
 * as a regular Temporal activity, or as a child workflow.</p>
 */
public enum ExecutionMode {

    /** Runs inside the workflow thread. Must be deterministic. */
    WORKFLOW,

    /** Runs as a Temporal local activity (fast external calls). */
    LOCAL_ACTIVITY,

    /** Runs as a Temporal activity (durable, long running). */
    ACTIVITY,

    /** Runs as a child workflow (for complex sub-pipelines). */
    CHILD_WORKFLOW
}

