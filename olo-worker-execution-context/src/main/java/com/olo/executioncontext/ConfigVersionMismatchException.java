package com.olo.executioncontext;

/**
 * Thrown when execution version pinning is requested ({@code routing.configVersion}) but the
 * loaded pipeline config version does not match. Resolution policy: the run fails; no fallback
 * to a different version. Callers should use {@link LocalContext#forQueue(String, String, String)}
 * which throws this when requested version is non-null and does not equal the loaded version.
 */
public final class ConfigVersionMismatchException extends RuntimeException {

    private final String requestedVersion;
    private final String loadedVersion;

    public ConfigVersionMismatchException(String requestedVersion, String loadedVersion) {
        super(String.format("Config version mismatch: requested=%s, loaded=%s",
                requestedVersion, loadedVersion));
        this.requestedVersion = requestedVersion;
        this.loadedVersion = loadedVersion;
    }

    /** Version requested by the client (e.g. from routing.configVersion). */
    public String getRequestedVersion() {
        return requestedVersion;
    }

    /** Version of the config currently loaded in the global context. */
    public String getLoadedVersion() {
        return loadedVersion;
    }
}
