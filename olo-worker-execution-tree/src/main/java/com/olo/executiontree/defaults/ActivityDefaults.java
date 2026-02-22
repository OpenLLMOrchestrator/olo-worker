package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Activity execution defaults: payload, defaultTimeouts, retryPolicy. */
public final class ActivityDefaults {

    private final ActivityPayloadDefaults payload;
    private final ActivityDefaultTimeouts defaultTimeouts;
    private final RetryPolicyDefaults retryPolicy;

    @JsonCreator
    public ActivityDefaults(
            @JsonProperty("payload") ActivityPayloadDefaults payload,
            @JsonProperty("defaultTimeouts") ActivityDefaultTimeouts defaultTimeouts,
            @JsonProperty("retryPolicy") RetryPolicyDefaults retryPolicy) {
        this.payload = payload;
        this.defaultTimeouts = defaultTimeouts;
        this.retryPolicy = retryPolicy;
    }

    public ActivityPayloadDefaults getPayload() {
        return payload;
    }

    public ActivityDefaultTimeouts getDefaultTimeouts() {
        return defaultTimeouts;
    }

    public RetryPolicyDefaults getRetryPolicy() {
        return retryPolicy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActivityDefaults that = (ActivityDefaults) o;
        return Objects.equals(payload, that.payload)
                && Objects.equals(defaultTimeouts, that.defaultTimeouts)
                && Objects.equals(retryPolicy, that.retryPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, defaultTimeouts, retryPolicy);
    }
}
