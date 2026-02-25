package com.olo.plugin;

import java.util.Collections;
import java.util.Map;

/**
 * SPI for pluggable plugins. Implementations are discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/com.olo.plugin.PluginProvider). The worker registers each provider's
 * plugin for every tenant; no worker code changes are required for new plugins.
 * <p>
 * Evolution path: version and capability metadata support semantic compatibility, multi-team
 * deployments, and enterprise audit. Future extensions may use pluginId+version as a composite
 * key when multiple implementations of the same logical plugin coexist.
 */
public interface PluginProvider {

    /**
     * Plugin id (e.g. "GPT4_EXECUTOR", "QDRANT_VECTOR_STORE"). Must match scope and execution tree pluginRef.
     */
    String getPluginId();

    /**
     * Contract type for scope/config compatibility; one of {@link ContractType} constants.
     */
    String getContractType();

    /**
     * Plugin instance. Typically created from env (e.g. OLLAMA_BASE_URL) in the provider constructor.
     * Prefer {@link #createPlugin()} when per-node instances are required.
     */
    ExecutablePlugin getPlugin();

    /**
     * Creates a new plugin instance for a tree node. Used so each node gets its own instance;
     * when the same node runs again (e.g. in a loop), the same instance is reused (via run-scoped cache).
     * Default returns {@link #getPlugin()} for backward compatibility; override to return a new instance per call.
     */
    default ExecutablePlugin createPlugin() {
        return getPlugin();
    }

    /**
     * Plugin/contract version for compatibility and audit (e.g. "1.0", "2.1.0").
     * Used for config compatibility checks and future pluginId+version resolution.
     */
    default String getVersion() {
        return "1.0";
    }

    /**
     * Optional capability metadata for audit and semantic compatibility (e.g. supported operations,
     * API level, vendor). Empty by default. Immutable map recommended.
     */
    default Map<String, Object> getCapabilityMetadata() {
        return Collections.emptyMap();
    }

    /**
     * Whether this provider should be registered. Override to skip registration when env is unset (e.g. optional plugins).
     */
    default boolean isEnabled() {
        return true;
    }
}
