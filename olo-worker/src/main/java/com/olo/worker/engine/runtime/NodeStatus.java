package com.olo.worker.engine.runtime;

/**
 * Runtime status of a node in the execution tree. Single source of truth for traversal.
 */
public enum NodeStatus {
    /** Not yet run; eligible when parent is COMPLETED. */
    NOT_STARTED,
    /** Skipped (e.g. IF else-branch not taken). */
    SKIPPED,
    /** Completed successfully. */
    COMPLETED,
    /** Failed. */
    FAILED
}
