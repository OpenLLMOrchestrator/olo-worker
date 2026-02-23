package com.olo.annotations;

/**
 * Contract for resource cleanup when the worker or component is shutting down.
 * Plugins and features that hold resources (connections, threads, caches) should implement this
 * and release them in {@link #onExit()}. The worker invokes {@code onExit()} on all registered
 * plugins and features during shutdown, before the process exits.
 */
public interface ResourceCleanup {

    /**
     * Called once when the worker is shutting down. Implementations should release resources
     * (close connections, cancel in-flight work, flush caches). Exceptions should be logged
     * and not rethrown so other components still get a chance to clean up.
     */
    void onExit();
}
