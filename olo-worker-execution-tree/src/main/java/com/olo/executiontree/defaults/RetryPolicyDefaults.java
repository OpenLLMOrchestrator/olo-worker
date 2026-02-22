package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/** Retry policy defaults for activities. */
public final class RetryPolicyDefaults {

    private final int maximumAttempts;
    private final int initialIntervalSeconds;
    private final double backoffCoefficient;
    private final int maximumIntervalSeconds;
    private final List<String> nonRetryableErrors;

    @JsonCreator
    public RetryPolicyDefaults(
            @JsonProperty("maximumAttempts") Integer maximumAttempts,
            @JsonProperty("initialIntervalSeconds") Integer initialIntervalSeconds,
            @JsonProperty("backoffCoefficient") Double backoffCoefficient,
            @JsonProperty("maximumIntervalSeconds") Integer maximumIntervalSeconds,
            @JsonProperty("nonRetryableErrors") List<String> nonRetryableErrors) {
        this.maximumAttempts = maximumAttempts != null ? maximumAttempts : 3;
        this.initialIntervalSeconds = initialIntervalSeconds != null ? initialIntervalSeconds : 1;
        this.backoffCoefficient = backoffCoefficient != null ? backoffCoefficient : 2.0;
        this.maximumIntervalSeconds = maximumIntervalSeconds != null ? maximumIntervalSeconds : 60;
        this.nonRetryableErrors = nonRetryableErrors != null ? List.copyOf(nonRetryableErrors) : List.of();
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public int getInitialIntervalSeconds() {
        return initialIntervalSeconds;
    }

    public double getBackoffCoefficient() {
        return backoffCoefficient;
    }

    public int getMaximumIntervalSeconds() {
        return maximumIntervalSeconds;
    }

    public List<String> getNonRetryableErrors() {
        return nonRetryableErrors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RetryPolicyDefaults that = (RetryPolicyDefaults) o;
        return maximumAttempts == that.maximumAttempts
                && initialIntervalSeconds == that.initialIntervalSeconds
                && Double.compare(backoffCoefficient, that.backoffCoefficient) == 0
                && maximumIntervalSeconds == that.maximumIntervalSeconds
                && Objects.equals(nonRetryableErrors, that.nonRetryableErrors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maximumAttempts, initialIntervalSeconds, backoffCoefficient,
                maximumIntervalSeconds, nonRetryableErrors);
    }
}
