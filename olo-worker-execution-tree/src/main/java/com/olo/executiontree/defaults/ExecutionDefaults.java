package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Root-level execution defaults: engine (e.g. TEMPORAL), temporal settings,
 * and activity defaults (payload, timeouts, retry policy).
 */
public final class ExecutionDefaults {

    private final String engine;
    private final TemporalDefaults temporal;
    private final ActivityDefaults activity;

    @JsonCreator
    public ExecutionDefaults(
            @JsonProperty("engine") String engine,
            @JsonProperty("temporal") TemporalDefaults temporal,
            @JsonProperty("activity") ActivityDefaults activity) {
        this.engine = engine;
        this.temporal = temporal;
        this.activity = activity;
    }

    public String getEngine() {
        return engine;
    }

    public TemporalDefaults getTemporal() {
        return temporal;
    }

    public ActivityDefaults getActivity() {
        return activity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionDefaults that = (ExecutionDefaults) o;
        return Objects.equals(engine, that.engine)
                && Objects.equals(temporal, that.temporal)
                && Objects.equals(activity, that.activity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(engine, temporal, activity);
    }
}
