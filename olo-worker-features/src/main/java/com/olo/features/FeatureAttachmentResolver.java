package com.olo.features;

import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the effective pre/post feature lists for a tree node by merging:
 * <ul>
 *   <li>Node's explicit {@code preExecution} / {@code postExecution}</li>
 *   <li>Node's {@code features} (merged by each feature's phase)</li>
 *   <li>Pipeline/global enabled features (when queue ends with {@code -debug}, {@code debug} is added if in feature list)</li>
 *   <li>Node's {@code featureRequired} (always added)</li>
 *   <li>Node's {@code featureNotRequired} (excluded even when globally enabled)</li>
 * </ul>
 * The builder ensures that for a debug queue, the Debug feature is attached to all nodes (matching the feature's
 * applicableNodeTypes, e.g. "*") unless the node opts out via {@code featureNotRequired}.
 */
public final class FeatureAttachmentResolver {

    private static final String DEBUG_QUEUE_SUFFIX = "-debug";
    private static final String DEBUG_FEATURE_NAME = "debug";

    /**
     * Resolves the effective pre and post feature name lists for the given node.
     *
     * @param node                        the execution tree node
     * @param queueName                   task queue name (e.g. olo-chat-queue-oolama-debug)
     * @param pipelineScopeFeatureNames  feature names from pipeline scope (and/or root allowed list)
     * @param registry                    feature registry to check applicability and phase
     * @return resolved pre and post feature name lists (no duplicates, order preserved)
     */
    public static ResolvedPrePost resolve(
            ExecutionTreeNode node,
            String queueName,
            List<String> pipelineScopeFeatureNames,
            FeatureRegistry registry) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(registry, "registry");

        Set<String> enabledForAttachment = new LinkedHashSet<>();
        if (pipelineScopeFeatureNames != null) {
            for (Object o : pipelineScopeFeatureNames) {
                String name = o != null ? o.toString().trim() : null;
                if (name != null && !name.isEmpty()) enabledForAttachment.add(name);
            }
        }
        if (queueName != null && queueName.endsWith(DEBUG_QUEUE_SUFFIX)) {
            enabledForAttachment.add(DEBUG_FEATURE_NAME);
        }

        List<String> notRequired = node.getFeatureNotRequired();
        String nodeType = node.getNodeType();
        String type = node.getType();

        List<String> pre = new ArrayList<>(node.getPreExecution());
        List<String> post = new ArrayList<>(node.getPostExecution());

        for (String f : node.getFeatures()) {
            if (f == null || notRequired.contains(f)) continue;
            FeatureRegistry.FeatureEntry e = registry.get(f);
            if (e == null) continue;
            if (!e.appliesTo(nodeType, type)) continue;
            if (e.isPre() && !pre.contains(f)) pre.add(f);
            if (e.isPost() && !post.contains(f)) post.add(f);
        }

        for (String f : enabledForAttachment) {
            if (notRequired.contains(f)) continue;
            FeatureRegistry.FeatureEntry e = registry.get(f);
            if (e == null) continue;
            if (!e.appliesTo(nodeType, type)) continue;
            if (e.isPre() && !pre.contains(f)) pre.add(f);
            if (e.isPost() && !post.contains(f)) post.add(f);
        }

        for (String f : node.getFeatureRequired()) {
            if (f == null) continue;
            FeatureRegistry.FeatureEntry e = registry.get(f);
            if (e == null) continue;
            if (e.isPre() && !pre.contains(f)) pre.add(f);
            if (e.isPost() && !post.contains(f)) post.add(f);
        }

        return new ResolvedPrePost(pre, post);
    }
}
