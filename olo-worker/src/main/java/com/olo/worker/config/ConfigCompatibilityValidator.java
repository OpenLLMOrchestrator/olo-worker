package com.olo.worker.config;

import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.scope.FeatureDef;
import com.olo.executiontree.scope.PluginDef;
import com.olo.executiontree.scope.Scope;
import com.olo.features.FeatureRegistry;
import com.olo.plugin.PluginRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates config file version, plugin contract versions, and feature contract versions
 * so that config updates are rejected before breaking changes (fail-fast).
 *
 * @see <a href="../../../docs/versioned-config-strategy.md">Versioned config strategy</a>
 */
public final class ConfigCompatibilityValidator {

    private final String supportedConfigVersionMin;
    private final String supportedConfigVersionMax;
    private final PluginRegistry pluginRegistry;
    private final FeatureRegistry featureRegistry;

    /**
     * @param supportedConfigVersionMin minimum supported config version (e.g. "2.0"); null = skip config version check
     * @param supportedConfigVersionMax maximum supported config version (e.g. "2.x"); null = skip
     * @param pluginRegistry             used to resolve runtime plugin contract versions; null = skip plugin check
     * @param featureRegistry            used to resolve runtime feature contract versions; null = skip feature check
     */
    public ConfigCompatibilityValidator(String supportedConfigVersionMin,
                                         String supportedConfigVersionMax,
                                         PluginRegistry pluginRegistry,
                                         FeatureRegistry featureRegistry) {
        this.supportedConfigVersionMin = supportedConfigVersionMin;
        this.supportedConfigVersionMax = supportedConfigVersionMax;
        this.pluginRegistry = pluginRegistry;
        this.featureRegistry = featureRegistry;
    }

    /**
     * Validates the pipeline configuration. Returns a result with errors if any check fails.
     */
    public ValidationResult validate(PipelineConfiguration config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            errors.add("Config is null");
            return ValidationResult.failure(errors);
        }

        // Config file version
        String configVersion = config.getVersion();
        if (supportedConfigVersionMin != null || supportedConfigVersionMax != null) {
            if (configVersion == null || configVersion.isBlank()) {
                errors.add("Config version is missing; required when supported version range is set");
            } else {
                if (supportedConfigVersionMin != null && !supportedConfigVersionMin.isBlank()
                        && compareVersions(configVersion, supportedConfigVersionMin) < 0) {
                    errors.add("Config version " + configVersion + " is below supported minimum " + supportedConfigVersionMin);
                }
                if (supportedConfigVersionMax != null && !supportedConfigVersionMax.isBlank()
                        && compareVersions(configVersion, supportedConfigVersionMax) > 0) {
                    errors.add("Config version " + configVersion + " is above supported maximum " + supportedConfigVersionMax);
                }
            }
        }

        Map<String, PipelineDefinition> pipelines = config.getPipelines();
        if (pipelines == null) return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);

        for (Map.Entry<String, PipelineDefinition> e : pipelines.entrySet()) {
            String pipelineName = e.getKey();
            PipelineDefinition def = e.getValue();
            if (def == null) continue;
            Scope scope = def.getScope();
            if (scope == null) continue;

            // Plugin contract versions
            if (pluginRegistry != null && scope.getPlugins() != null) {
                for (PluginDef pluginDef : scope.getPlugins()) {
                    if (pluginDef == null) continue;
                    String pluginId = pluginDef.getId();
                    if (pluginId == null || pluginId.isBlank()) continue;
                    String expectedVersion = pluginDef.getContractVersion();
                    if (expectedVersion == null || expectedVersion.isBlank()) continue; // config does not require a version
                    String actualVersion = pluginRegistry.getContractVersion(pluginId);
                    if (actualVersion == null) {
                        if (pluginRegistry.get(pluginId) == null) {
                            errors.add("Pipeline " + pipelineName + ": plugin " + pluginId + " not registered");
                        } else {
                            errors.add("Pipeline " + pipelineName + ": plugin " + pluginId + " has no contract version (config expects " + expectedVersion + ")");
                        }
                    } else if (!Objects.equals(actualVersion, expectedVersion)) {
                        errors.add("Pipeline " + pipelineName + ": plugin " + pluginId + " contract version mismatch (config: " + expectedVersion + ", runtime: " + actualVersion + ")");
                    }
                }
            }

            // Feature contract versions
            if (featureRegistry != null && scope.getFeatures() != null) {
                for (FeatureDef featureDef : scope.getFeatures()) {
                    if (featureDef == null) continue;
                    String featureId = featureDef.getId();
                    if (featureId == null || featureId.isBlank()) continue;
                    String expectedVersion = featureDef.getContractVersion();
                    if (expectedVersion == null || expectedVersion.isBlank()) continue;
                    String actualVersion = featureRegistry.getContractVersion(featureId);
                    if (actualVersion == null) {
                        if (featureRegistry.get(featureId) == null) {
                            errors.add("Pipeline " + pipelineName + ": feature " + featureId + " not registered");
                        } else {
                            errors.add("Pipeline " + pipelineName + ": feature " + featureId + " has no contract version (config expects " + expectedVersion + ")");
                        }
                    } else if (!Objects.equals(actualVersion, expectedVersion)) {
                        errors.add("Pipeline " + pipelineName + ": feature " + featureId + " contract version mismatch (config: " + expectedVersion + ", runtime: " + actualVersion + ")");
                    }
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Validates and throws ConfigIncompatibleException if invalid.
     */
    public void validateOrThrow(PipelineConfiguration config) {
        ValidationResult result = validate(config);
        if (!result.isValid()) {
            throw new ConfigIncompatibleException(result);
        }
    }

    /** Simple version compare: 1.0 vs 1.0 = 0, 2.0 vs 1.0 = 1, 1.0 vs 2.0 = -1. "x" suffix treated as high (e.g. 2.x >= 2.0). */
    private static int compareVersions(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null || a.isBlank()) return -1;
        if (b == null || b.isBlank()) return 1;
        String an = a.trim();
        String bn = b.trim();
        if (an.endsWith(".x")) an = an.substring(0, an.length() - 2) + ".999";
        if (bn.endsWith(".x")) bn = bn.substring(0, bn.length() - 2) + ".999";
        String[] ap = an.split("\\.");
        String[] bp = bn.split("\\.");
        for (int i = 0; i < Math.max(ap.length, bp.length); i++) {
            int av = i < ap.length ? parseSegment(ap[i]) : 0;
            int bv = i < bp.length ? parseSegment(bp[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
