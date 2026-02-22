package com.olo.config;

import com.olo.input.producer.CacheWriter;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Objects;

/**
 * Redis-backed {@link CacheWriter} used by {@link OloSessionCache} to push
 * serialized workflow input to the session key during the initialize phase.
 */
final class RedisCacheWriter implements CacheWriter {

    private final JedisPool pool;

    RedisCacheWriter(String host, int port) {
        this(host, port, new JedisPoolConfig());
    }

    RedisCacheWriter(String host, int port, JedisPoolConfig poolConfig) {
        Objects.requireNonNull(host, "host");
        this.pool = new JedisPool(poolConfig, host, port);
    }

    @Override
    public void put(String key, String value) {
        try (var jedis = pool.getResource()) {
            jedis.set(key, value);
        }
    }
}
