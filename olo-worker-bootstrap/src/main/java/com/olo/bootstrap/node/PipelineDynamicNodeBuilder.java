package com.olo.bootstrap.node;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.DynamicNodeSpec;
import com.olo.node.PipelineFeatureContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bootstrap implementation of {@link DynamicNodeBuilder}. Builds a PLUGIN node from the spec
 * and attaches pipeline/queue features via {@link PipelineNodeFeatureEnricher}, so the planner
 * receives a fully designed node ready to attach to the tree.
 */
public final class PipelineDynamicNodeBuilder implements DynamicNodeBuilder {

    private static final PipelineDynamicNodeBuilder INSTANCE = new PipelineDynamicNodeBuilder();
    private static final List<String> EMPTY = List.of();

    public static DynamicNodeBuilder getInstance() {
        return INSTANCE;
    }

    private PipelineDynamicNodeBuilder() {
    }

    @Override
    public ExecutionTreeNode buildNode(DynamicNodeSpec spec, PipelineFeatureContext context) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(context, "context");
        String id = spec.id() != null && !spec.id().isBlank() ? spec.id() : java.util.UUID.randomUUID().toString();
        String displayName = spec.displayName() != null && !spec.displayName().isBlank() ? spec.displayName() : "step";
        String pluginRef = spec.pluginRef();
        ExecutionTreeNode raw = new ExecutionTreeNode(
                id,
                displayName,
                NodeType.PLUGIN,
                List.of(),
                "PLUGIN",
                pluginRef,
                spec.inputMappings(),
                spec.outputMappings(),
                EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY,
                Map.of(),
                null, null, null
        );
        return PipelineNodeFeatureEnricher.getInstance().enrich(raw, context);
    }
}
