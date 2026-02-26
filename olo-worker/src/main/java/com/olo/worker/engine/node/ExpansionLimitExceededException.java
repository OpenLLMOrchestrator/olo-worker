package com.olo.worker.engine.node;

/**
 * Thrown by {@link DynamicNodeFactoryImpl#expand} when an expansion would exceed
 * one of {@link ExpansionLimits}. Indicates which limit was exceeded for logging and handling.
 */
public final class ExpansionLimitExceededException extends IllegalStateException {

    private final String limitName;
    private final int value;
    private final int limit;

    public ExpansionLimitExceededException(String limitName, int value, int limit) {
        super(String.format("Expansion limit exceeded: %s (value=%d, limit=%d)", limitName, value, limit));
        this.limitName = limitName;
        this.value = value;
        this.limit = limit;
    }

    public ExpansionLimitExceededException(String message) {
        super(message);
        this.limitName = null;
        this.value = 0;
        this.limit = 0;
    }

    public String getLimitName() {
        return limitName;
    }

    public int getValue() {
        return value;
    }

    public int getLimit() {
        return limit;
    }
}
