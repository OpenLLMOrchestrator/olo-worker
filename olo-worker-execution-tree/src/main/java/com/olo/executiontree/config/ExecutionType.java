package com.olo.executiontree.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the execution tree is run: synchronously (default) or asynchronously.
 * When {@link #ASYNC}, every node except JOIN runs in a worker thread; JOIN runs synchronously to merge.
 */
public enum ExecutionType {
    /** Execute all nodes synchronously in the calling thread (default). */
    SYNC,
    /** Execute nodes asynchronously (thread pool) except JOIN, which runs sync to merge branches. */
    ASYNC;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static ExecutionType fromValue(String value) {
        if (value == null || value.isBlank()) return SYNC;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SYNC;
        }
    }
}
