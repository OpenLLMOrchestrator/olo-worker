package com.olo.executiontree.scope;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility to resolve which feature names should be attached to dynamic/planner-added nodes
 * based on pipeline scope and queue name. Used by bootstrap to implement the protocol
 * dynamic-node builder/enricher without depending on the worker or feature registry.
 */
public final class PipelineFeatureNames {

    private static final String DEBUG_QUEUE_SUFFIX = "-debug";
    private static final String DEBUG_FEATURE_NAME = "debug";

    private PipelineFeatureNames() {
    }

    /**
     * Returns feature names to attach to a dynamic node so it gets the same behavior as
     * static nodes (pipeline scope features + debug when queue ends with {@code -debug}).
     *
     * @param scope     pipeline scope (scope.features)
     * @param queueName task queue name (e.g. for -debug)
     * @return list of feature names; never null
     */
    public static List<String> getFeatureNamesForDynamicNode(Scope scope, String queueName) {
        List<String> names = new ArrayList<>(getScopeFeatureIds(scope));
        if (queueName != null && queueName.endsWith(DEBUG_QUEUE_SUFFIX) && !names.contains(DEBUG_FEATURE_NAME)) {
            names.add(DEBUG_FEATURE_NAME);
        }
        return names;
    }

    private static List<String> getScopeFeatureIds(Scope scope) {
        List<String> out = new ArrayList<>();
        if (scope == null || scope.getFeatures() == null) return out;
        for (FeatureDef f : scope.getFeatures()) {
            if (f != null && f.getId() != null && !f.getId().isBlank()) {
                out.add(f.getId().trim());
            }
        }
        return out;
    }
}
