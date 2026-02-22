package com.olo.executiontree.scope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Feature definition in scope: id, optional displayName, optional contractVersion. */
public final class FeatureDef {

    private final String id;
    private final String displayName;
    private final String contractVersion;

    @JsonCreator
    public FeatureDef(
            @JsonProperty("id") String id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("contractVersion") String contractVersion) {
        this.id = id;
        this.displayName = displayName;
        this.contractVersion = contractVersion;
    }

    public String getId() {
        return id;
    }

    /** Human-readable name for UI (optional). */
    public String getDisplayName() {
        return displayName;
    }

    /** Contract version (e.g. 1.0) for compatibility checks; null = any. */
    public String getContractVersion() {
        return contractVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeatureDef that = (FeatureDef) o;
        return Objects.equals(id, that.id) && Objects.equals(displayName, that.displayName)
                && Objects.equals(contractVersion, that.contractVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, contractVersion);
    }
}
