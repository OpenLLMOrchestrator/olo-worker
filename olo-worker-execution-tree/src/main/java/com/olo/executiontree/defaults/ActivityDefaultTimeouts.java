package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Activity default timeouts (seconds). */
public final class ActivityDefaultTimeouts {

    private final int scheduleToStartSeconds;
    private final int startToCloseSeconds;
    private final int scheduleToCloseSeconds;

    @JsonCreator
    public ActivityDefaultTimeouts(
            @JsonProperty("scheduleToStartSeconds") Integer scheduleToStartSeconds,
            @JsonProperty("startToCloseSeconds") Integer startToCloseSeconds,
            @JsonProperty("scheduleToCloseSeconds") Integer scheduleToCloseSeconds) {
        this.scheduleToStartSeconds = scheduleToStartSeconds != null ? scheduleToStartSeconds : 0;
        this.startToCloseSeconds = startToCloseSeconds != null ? startToCloseSeconds : 0;
        this.scheduleToCloseSeconds = scheduleToCloseSeconds != null ? scheduleToCloseSeconds : 0;
    }

    public int getScheduleToStartSeconds() {
        return scheduleToStartSeconds;
    }

    public int getStartToCloseSeconds() {
        return startToCloseSeconds;
    }

    public int getScheduleToCloseSeconds() {
        return scheduleToCloseSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActivityDefaultTimeouts that = (ActivityDefaultTimeouts) o;
        return scheduleToStartSeconds == that.scheduleToStartSeconds
                && startToCloseSeconds == that.startToCloseSeconds
                && scheduleToCloseSeconds == that.scheduleToCloseSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheduleToStartSeconds, startToCloseSeconds, scheduleToCloseSeconds);
    }
}
