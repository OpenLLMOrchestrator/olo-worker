package com.olo.executiontree.scope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Feature definition in scope: id and optional displayName. */
public final class FeatureDef {

    private final String id;
    private final String displayName;

    @JsonCreator
    public FeatureDef(
            @JsonProperty("id") String id,
            @JsonProperty("displayName") String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    /** Human-readable name for UI (optional). */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeatureDef that = (FeatureDef) o;
        return Objects.equals(id, that.id) && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName);
    }
}
