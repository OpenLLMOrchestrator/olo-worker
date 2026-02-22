package com.olo.input.producer;

import java.util.Objects;

/**
 * Standard key format for cache storage of input values. Both producer and consumer use this so keys are consistent.
 */
public final class InputStorageKeys {

    private static final String PREFIX = "olo:worker:";

    private InputStorageKeys() {
    }

    /**
     * Builds the cache key for an input value. Format: {@code olo:worker:{transactionId}:input:{inputName}}.
     *
     * @param transactionId the workflow/transaction id
     * @param inputName     the input name
     * @return the cache key
     */
    public static String cacheKey(String transactionId, String inputName) {
        return PREFIX + Objects.requireNonNull(transactionId, "transactionId")
                + ":input:" + Objects.requireNonNull(inputName, "inputName");
    }
}
