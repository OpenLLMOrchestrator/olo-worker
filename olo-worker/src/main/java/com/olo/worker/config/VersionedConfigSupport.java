package com.olo.worker.config;

import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.load.GlobalConfigurationContext;
import com.olo.features.FeatureRegistry;
import com.olo.plugin.PluginRegistry;

/**
 * Support for versioned config: run all version checks (config version, plugin contract, feature contract)
 * during bootstrap or when configuration changes, and only then accept the config.
 *
 * <ul>
 *   <li><b>Bootstrap:</b> Validation runs in {@link com.olo.worker.OloWorkerApplication} after config load
 *       and after plugins/features are registered; startup fails if any config is incompatible.</li>
 *   <li><b>Configuration change:</b> Before putting new config into the global context (e.g. hot reload,
 *       admin update), call {@link #validateAndPut(String, PipelineConfiguration)} so version checks
 *       run before the config is stored. If validation fails, config is not put and an exception is thrown.</li>
 * </ul>
 *
 * @see <a href="../../../docs/versioned-config-strategy.md">Versioned config strategy</a>
 */
public final class VersionedConfigSupport {

    private VersionedConfigSupport() {
    }

    /**
     * Validates the pipeline configuration (config version, plugin contract version, feature contract version)
     * and, if valid, puts it into the global context for the given queue. Use this whenever configuration
     * is updated (e.g. reload, admin API) so version checks run before the new config is used.
     *
     * @param queueName task queue name
     * @param config    pipeline configuration to validate and store
     * @throws ConfigIncompatibleException if validation fails (config is not put)
     */
    public static void validateAndPut(String queueName, PipelineConfiguration config) {
        ConfigCompatibilityValidator validator = new ConfigCompatibilityValidator(
                null, null,
                PluginRegistry.getInstance(),
                FeatureRegistry.getInstance());
        validator.validateOrThrow(config);
        GlobalConfigurationContext.put(queueName, config);
    }
}
