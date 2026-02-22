package com.olo.executiontree.load;

import java.util.Optional;

/**
 * Source for pipeline configuration: Redis (by key) and DB (by queue + version).
 * Implementations are provided by the runtime (e.g. olo-worker or olo-worker-configuration).
 */
public interface ConfigSource {

    /**
     * Gets configuration JSON from Redis (or other cache) by key.
     *
     * @param key full key (e.g. {@link ConfigKeyBuilder#redisKey(String, String, String)})
     * @return configuration JSON if present
     */
    Optional<String> getFromCache(String key);

    /**
     * Gets configuration JSON from the database by queue name and version.
     *
     * @param queueName task queue name (e.g. chat-queue-oolama)
     * @param version   config version (e.g. 1.0)
     * @return configuration JSON if present
     */
    Optional<String> getFromDb(String queueName, String version);
}
