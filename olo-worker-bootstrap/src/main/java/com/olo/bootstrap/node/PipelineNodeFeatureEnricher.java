package com.olo.bootstrap.node;

import com.olo.executiontree.scope.PipelineFeatureNames;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.node.NodeFeatureEnricher;
import com.olo.node.PipelineFeatureContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrap implementation of {@link NodeFeatureEnricher}. Attaches pipeline and queue-based
 * features using {@link PipelineFeatureNames} (execution-tree) so there is no dependency on the worker.
 */
public final class PipelineNodeFeatureEnricher implements NodeFeatureEnricher {

    private static final PipelineNodeFeatureEnricher INSTANCE = new PipelineNodeFeatureEnricher();

    public static PipelineNodeFeatureEnricher getInstance() {
        return INSTANCE;
    }

    private PipelineNodeFeatureEnricher() {
    }

    @Override
    public ExecutionTreeNode enrich(ExecutionTreeNode node, PipelineFeatureContext context) {
        if (node == null || context == null) return node;
        List<String> pipelineFeatures = PipelineFeatureNames.getFeatureNamesForDynamicNode(
                context.getScope(), context.getQueueName());
        if (pipelineFeatures.isEmpty()) return node;
        List<String> existing = node.getFeatures();
        List<String> merged = new ArrayList<>(existing != null ? existing : List.of());
        for (String f : pipelineFeatures) {
            if (f != null && !f.isBlank() && !merged.contains(f)) {
                merged.add(f);
            }
        }
        if (merged.equals(existing)) return node;
        return ExecutionTreeNode.withFeatures(node, merged);
    }
}
