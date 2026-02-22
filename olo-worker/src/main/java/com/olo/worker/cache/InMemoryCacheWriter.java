package com.olo.worker.cache;

import com.olo.input.producer.CacheWriter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link CacheWriter}. Useful for local/dev when Redis is not required.
 * Replace with a Redis-backed implementation in production.
 */
public final class InMemoryCacheWriter implements CacheWriter {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value) {
        store.put(key, value);
    }

    public String get(String key) {
        return store.get(key);
    }
}
