package com.olo.executiontree.load;

import java.util.Objects;

/**
 * Builds the Redis/cache key for pipeline configuration.
 * Example: {@code olo:engine:config:olo-chat-queue-oolama:1.0} when queueId is {@code olo-chat-queue-oolama} and version is {@code 1.0}.
 * The queueId can be the task queue name or a prefixed form (e.g. olo- + queue name).
 */
public final class ConfigKeyBuilder {

    private static final String DEFAULT_PREFIX = "olo:engine:config";

    private final String prefix;

    public ConfigKeyBuilder(String prefix) {
        this.prefix = prefix != null && !prefix.isBlank() ? prefix.trim() : DEFAULT_PREFIX;
    }

    public ConfigKeyBuilder() {
        this(DEFAULT_PREFIX);
    }

    /**
     * Full Redis key for the given queue and version.
     *
     * @param queueId queue identifier (e.g. task queue name or prefixed like olo-chat-queue-oolama)
     * @param version version string (e.g. 1.0)
     * @return key e.g. olo:engine:config:olo-chat-queue-oolama:1.0
     */
    public String redisKey(String queueId, String version) {
        String q = queueId != null ? queueId : "";
        String v = version != null ? version : "";
        return prefix + ":" + q + ":" + v;
    }

    /**
     * Static helper: build key with default prefix.
     */
    public static String redisKey(String prefix, String queueId, String version) {
        return new ConfigKeyBuilder(prefix).redisKey(queueId, version);
    }
}
