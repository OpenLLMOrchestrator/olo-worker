package com.olo.executiontree.defaults;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Activity default timeouts (seconds). Used for node-level overrides; resolved at bootstrap: current → parent → global. */
public final class ActivityDefaultTimeouts {

    /** Default when no global default is configured (scheduleToStart 6000, startToClose 3000, scheduleToClose 30000). */
    public static final ActivityDefaultTimeouts GLOBAL_DEFAULT = new ActivityDefaultTimeouts(6000, 3000, 30000);

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

    /**
     * Resolves one value: use node value if non-null and positive, else use fallback's value.
     */
    public static ActivityDefaultTimeouts resolve(
            Integer nodeScheduleToStart,
            Integer nodeStartToClose,
            Integer nodeScheduleToClose,
            ActivityDefaultTimeouts fallback) {
        if (fallback == null) fallback = GLOBAL_DEFAULT;
        int schedule = (nodeScheduleToStart != null && nodeScheduleToStart > 0) ? nodeScheduleToStart : fallback.getScheduleToStartSeconds();
        int start = (nodeStartToClose != null && nodeStartToClose > 0) ? nodeStartToClose : fallback.getStartToCloseSeconds();
        int close = (nodeScheduleToClose != null && nodeScheduleToClose > 0) ? nodeScheduleToClose : fallback.getScheduleToCloseSeconds();
        return new ActivityDefaultTimeouts(schedule, start, close);
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
