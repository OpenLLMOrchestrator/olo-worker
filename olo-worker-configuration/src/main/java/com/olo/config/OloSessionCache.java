package com.olo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.input.model.WorkflowInput;
import com.olo.input.producer.SessionUserInputStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Session cache for the initialize phase: serializes workflow input and pushes it to Redis
 * at the session key ({@code OLO_SESSION_DATA}<transactionId>{@code :USERINPUT}).
 * Serialization excludes null values from JSON.
 * <p>
 * User code should call {@link #cacheUpdate(WorkflowInput)} during workflow start / initialize.
 */
public final class OloSessionCache {

    private static final Logger log = LoggerFactory.getLogger(OloSessionCache.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final OloConfig config;
    private final RedisCacheWriter redisWriter;

    /**
     * Creates a session cache that uses Redis from the given config (OLO_CACHE_HOST, OLO_CACHE_PORT, OLO_SESSION_DATA).
     */
    public OloSessionCache(OloConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.redisWriter = new RedisCacheWriter(config.getCacheHost(), config.getCachePort());
        log.info("OloSessionCache connected to {}:{}", config.getCacheHost(), config.getCachePort());
    }

    /**
     * Serializes the workflow input (excluding null fields from JSON) and pushes it to Redis at the session USERINPUT key.
     * Call this during the initialize phase.
     *
     * @param input workflow input (version, inputs, context, routing, metadata)
     */
    public void cacheUpdate(WorkflowInput input) {
        Objects.requireNonNull(input, "input");
        String transactionId = input.getRouting() != null ? input.getRouting().getTransactionId() : null;
        String key = SessionUserInputStorage.userInputKey(config.getSessionDataPrefix(), transactionId);
        String value = toJsonExcludingNulls(input);
        redisWriter.put(key, value);
        log.debug("Cache update: session USERINPUT for transactionId={}", transactionId);
    }

    private static String toJsonExcludingNulls(WorkflowInput input) {
        try {
            return JSON_MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize workflow input", e);
        }
    }
}
