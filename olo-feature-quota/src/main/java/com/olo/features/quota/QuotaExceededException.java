package com.olo.features.quota;

/**
 * Thrown when the tenant's active workflow count exceeds the configured soft or hard limit.
 * Thrown by {@link QuotaFeature} in PRE phase (fail fast, no blocking).
 * The activity's finally block will still run, so Redis DECR for the run is guaranteed.
 */
public final class QuotaExceededException extends RuntimeException {

    private final String tenantId;
    private final long currentUsage;
    private final long limit;
    private final boolean hardLimit;

    public QuotaExceededException(String tenantId, long currentUsage, long limit, boolean hardLimit) {
        super(String.format("Quota exceeded for tenant=%s: usage=%d limit=%d (%s)",
                tenantId, currentUsage, limit, hardLimit ? "hard" : "soft"));
        this.tenantId = tenantId;
        this.currentUsage = currentUsage;
        this.limit = limit;
        this.hardLimit = hardLimit;
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getCurrentUsage() {
        return currentUsage;
    }

    public long getLimit() {
        return limit;
    }

    public boolean isHardLimit() {
        return hardLimit;
    }
}
