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
 *   <li>Node's explicit {@code preExecution}, {@code postSuccessExecution}, {@code postErrorExecution}, {@code finallyExecution}</li>
 *   <li>Legacy {@code postExecution} (merged into all three post lists)</li>
 *   <li>Node's {@code features} (merged by each feature's phase)</li>
 *   <li>Pipeline/global enabled features (when queue ends with {@code -debug}, {@code debug} is added)</li>
 *   <li>Node's {@code featureRequired}</li>
 *   <li>Node's {@code featureNotRequired} (excluded)</li>
 * </ul>
 * Returns separate lists for postSuccess, postError, and finally so the executor can run the right hooks on success vs exception.
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
     * @return resolved pre, postSuccess, postError, finally lists (no duplicates, order preserved)
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
        String type = node.getType().getTypeName();

        List<String> pre = new ArrayList<>(node.getPreExecution());
        List<String> postSuccess = new ArrayList<>(node.getPostSuccessExecution());
        List<String> postError = new ArrayList<>(node.getPostErrorExecution());
        List<String> finallyList = new ArrayList<>(node.getFinallyExecution());
        // Legacy: postExecution goes into all three post lists
        for (String f : node.getPostExecution()) {
            if (f == null) continue;
            if (!postSuccess.contains(f)) postSuccess.add(f);
            if (!postError.contains(f)) postError.add(f);
            if (!finallyList.contains(f)) finallyList.add(f);
        }

        addFeaturesByPhase(node.getFeatures(), notRequired, nodeType, type, registry, pre, postSuccess, postError, finallyList);
        addFeaturesByPhase(enabledForAttachment, notRequired, nodeType, type, registry, pre, postSuccess, postError, finallyList);
        addFeaturesByPhase(node.getFeatureRequired(), null, nodeType, type, registry, pre, postSuccess, postError, finallyList);

        return new ResolvedPrePost(pre, postSuccess, postError, finallyList);
    }

    private static void addFeaturesByPhase(
            Iterable<String> featureNames,
            List<String> exclude,
            String nodeType,
            String type,
            FeatureRegistry registry,
            List<String> pre,
            List<String> postSuccess,
            List<String> postError,
            List<String> finallyList) {
        if (featureNames == null) return;
        for (String f : featureNames) {
            if (f == null || (exclude != null && exclude.contains(f))) continue;
            FeatureRegistry.FeatureEntry e = registry.get(f);
            if (e == null) continue;
            if (!e.appliesTo(nodeType, type)) continue;
            if (e.isPre() && !pre.contains(f)) pre.add(f);
            if (e.isPostSuccess() && !postSuccess.contains(f)) postSuccess.add(f);
            if (e.isPostError() && !postError.contains(f)) postError.add(f);
            if (e.isFinally() && !finallyList.contains(f)) finallyList.add(f);
        }
    }
}
