package com.olo.worker.engine;

import com.olo.executiontree.scope.FeatureDef;
import com.olo.executiontree.scope.Scope;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.features.FeatureAttachmentResolver;
import com.olo.features.FeatureRegistry;
import com.olo.features.ResolvedPrePost;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single responsibility: resolve the effective pre/post feature list for a node.
 * Delegates to {@link FeatureAttachmentResolver} with scope feature names derived from pipeline scope.
 */
public final class FeatureResolver {

    /**
     * Resolves pre/post feature names for the given node.
     *
     * @param node      execution tree node
     * @param queueName task queue name (e.g. for -debug auto-attach)
     * @param scope     pipeline scope (plugins, features)
     * @param registry  feature registry
     * @return resolved pre and post feature name lists
     */
    public static ResolvedPrePost resolve(
            ExecutionTreeNode node,
            String queueName,
            Scope scope,
            FeatureRegistry registry) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(registry, "registry");
        List<String> scopeFeatureNames = getScopeFeatureNames(scope);
        return FeatureAttachmentResolver.resolve(node, queueName, scopeFeatureNames, registry);
    }

    private static List<String> getScopeFeatureNames(Scope scope) {
        List<String> names = new ArrayList<>();
        if (scope == null || scope.getFeatures() == null) return names;
        for (FeatureDef f : scope.getFeatures()) {
            if (f != null && f.getId() != null && !f.getId().isBlank()) {
                names.add(f.getId().trim());
            }
        }
        return names;
    }

    private FeatureResolver() {
    }
}
