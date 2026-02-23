package com.olo.executiontree.load;

/**
 * Sink for persisting pipeline configuration to Redis and DB.
 * When configuration is loaded from a local config file, the loader writes it back
 * via this interface so other containers can use it.
 * Implementations are provided by the runtime (e.g. olo-worker or olo-worker-configuration).
 */
public interface ConfigSink {

    /**
     * Writes configuration JSON to Redis (or other cache) under the given key.
     *
     * @param key  full key (e.g. from {@link ConfigKeyBuilder#redisKey(String, String)})
     * @param json pipeline configuration JSON
     */
    void putInCache(String key, String json);

    /**
     * Writes configuration JSON to the database for the given queue and version.
     *
     * @param queueName task queue name (e.g. chat-queue-oolama)
     * @param version   config version (e.g. 1.0)
     * @param json      pipeline configuration JSON
     */
    void putInDb(String queueName, String version, String json);

    /**
     * Writes configuration JSON to the database for the given tenant, queue and version.
     * Tables should include tenant_id for multi-tenant separation.
     *
     * @param tenantId  tenant id
     * @param queueName task queue name
     * @param version   config version (e.g. 1.0)
     * @param json      pipeline configuration JSON
     */
    default void putInDb(String tenantId, String queueName, String version, String json) {
        putInDb(queueName, version, json);
    }
}
