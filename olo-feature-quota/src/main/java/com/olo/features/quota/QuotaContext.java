package com.olo.features.quota;

import com.olo.config.OloSessionCache;

/**
 * Static holder for the session cache so {@link QuotaFeature} can read the current
 * active workflow count from Redis. Set once at worker startup in OloWorkerApplication.
 */
public final class QuotaContext {

    private static volatile OloSessionCache sessionCache;

    private QuotaContext() {
    }

    /**
     * Sets the session cache used by the quota feature to read current usage.
     * Call once after creating OloSessionCache in the worker main.
     */
    public static void setSessionCache(OloSessionCache cache) {
        sessionCache = cache;
    }

    /**
     * Returns the session cache, or null if not set (quota feature will skip check).
     */
    public static OloSessionCache getSessionCache() {
        return sessionCache;
    }
}
