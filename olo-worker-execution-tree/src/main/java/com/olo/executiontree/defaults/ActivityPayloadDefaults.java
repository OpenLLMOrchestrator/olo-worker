package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Activity payload defaults: max accumulated/result output keys. */
public final class ActivityPayloadDefaults {

    private final int maxAccumulatedOutputKeys;
    private final int maxResultOutputKeys;

    @JsonCreator
    public ActivityPayloadDefaults(
            @JsonProperty("maxAccumulatedOutputKeys") Integer maxAccumulatedOutputKeys,
            @JsonProperty("maxResultOutputKeys") Integer maxResultOutputKeys) {
        this.maxAccumulatedOutputKeys = maxAccumulatedOutputKeys != null ? maxAccumulatedOutputKeys : 0;
        this.maxResultOutputKeys = maxResultOutputKeys != null ? maxResultOutputKeys : 0;
    }

    public int getMaxAccumulatedOutputKeys() {
        return maxAccumulatedOutputKeys;
    }

    public int getMaxResultOutputKeys() {
        return maxResultOutputKeys;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActivityPayloadDefaults that = (ActivityPayloadDefaults) o;
        return maxAccumulatedOutputKeys == that.maxAccumulatedOutputKeys
                && maxResultOutputKeys == that.maxResultOutputKeys;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAccumulatedOutputKeys, maxResultOutputKeys);
    }
}
