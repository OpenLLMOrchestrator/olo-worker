package com.olo.worker.engine.node;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.node.DynamicNodeExpansionRequest;
import com.olo.node.ExpandedNode;
import com.olo.node.ExpansionResult;
import com.olo.node.NodeFeatureEnricher;
import com.olo.node.NodeSpec;
import com.olo.node.PipelineFeatureContext;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Worker-owned implementation of {@link com.olo.node.DynamicNodeFactory}. Owns tree mutation,
 * structural validation, ID policy, and lifecycle transitions. Enforces {@link ExpansionLimits}
 * so dynamic injection is safe. Planner never touches the tree.
 */
public final class DynamicNodeFactoryImpl implements com.olo.node.DynamicNodeFactory {

    private static final Logger log = LoggerFactory.getLogger(DynamicNodeFactoryImpl.class);
    private static final List<String> EMPTY_FEATURES = List.of();

    private final RuntimeExecutionTree tree;
    private final PipelineFeatureContext pipelineFeatureContext;
    private final NodeFeatureEnricher nodeFeatureEnricher;
    private final ExpansionLimits limits;
    private final ExpansionState expansionState;

    public DynamicNodeFactoryImpl(RuntimeExecutionTree tree,
                                  PipelineFeatureContext pipelineFeatureContext,
                                  NodeFeatureEnricher nodeFeatureEnricher,
                                  ExpansionLimits limits,
                                  ExpansionState expansionState) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.pipelineFeatureContext = pipelineFeatureContext;
        this.nodeFeatureEnricher = nodeFeatureEnricher != null ? nodeFeatureEnricher : (n, c) -> n;
        this.limits = limits != null ? limits : ExpansionLimits.DEFAULT;
        this.expansionState = expansionState != null ? expansionState : new ExpansionState();
    }

    @Override
    public ExpansionResult expand(DynamicNodeExpansionRequest request) {
        Objects.requireNonNull(request, "request");
        String parentId = request.plannerNodeId();
        if (parentId == null || parentId.isBlank()) {
            throw new IllegalArgumentException("Expansion request plannerNodeId must be non-blank");
        }
        List<NodeSpec> children = request.children();
        if (children == null || children.isEmpty()) {
            if (log.isInfoEnabled()) log.info("Planner expansion skip | parentId={} | no child specs (parser returned empty)", parentId);
            return new ExpansionResult(List.of(), List.of());
        }

        int childCount = (int) children.stream()
                .filter(s -> s != null && s.pluginRef() != null && !s.pluginRef().isBlank())
                .count();
        if (childCount == 0) {
            if (log.isWarnEnabled()) log.warn("Planner expansion skip | parentId={} | all {} spec(s) have null/blank pluginRef", parentId, children.size());
            return new ExpansionResult(List.of(), List.of());
        }

        if (tree.hasPlannerExpanded(parentId)) {
            if (log.isInfoEnabled()) log.info("Planner expansion skip | parentId={} | already expanded (idempotent)", parentId);
            return buildResultFromExistingChildren(parentId, children);
        }

        if (childCount > limits.getMaxDynamicNodesPerPlanner()) {
            throw new ExpansionLimitExceededException("maxDynamicNodesPerPlanner", childCount, limits.getMaxDynamicNodesPerPlanner());
        }
        int currentTotal = tree.getTotalNodeCount();
        if (currentTotal + childCount > limits.getMaxTotalNodesPerRun()) {
            throw new ExpansionLimitExceededException("maxTotalNodesPerRun", currentTotal + childCount, limits.getMaxTotalNodesPerRun());
        }
        int depth = tree.getDepth(parentId);
        if (depth >= limits.getMaxExpansionDepth()) {
            throw new ExpansionLimitExceededException("maxExpansionDepth", depth, limits.getMaxExpansionDepth());
        }
        int invocations = expansionState.getPlannerInvocations();
        if (invocations >= limits.getMaxPlannerInvocationsPerRun()) {
            throw new ExpansionLimitExceededException("maxPlannerInvocationsPerRun", invocations, limits.getMaxPlannerInvocationsPerRun());
        }

        List<ExecutionTreeNode> nodes = new ArrayList<>(children.size());
        List<ExpandedNode> expandedNodes = new ArrayList<>(children.size());
        for (NodeSpec spec : children) {
            if (spec == null || spec.pluginRef() == null || spec.pluginRef().isBlank()) continue;
            String id = UUID.randomUUID().toString();
            String displayName = spec.displayName() != null && !spec.displayName().isBlank()
                    ? spec.displayName() : "step-" + spec.pluginRef();
            final ExecutionTreeNode raw;
            if ("PLANNER".equals(spec.nodeType())) {
                raw = new ExecutionTreeNode(
                        id,
                        displayName,
                        NodeType.PLANNER,
                        List.of(),
                        "PLANNER",
                        spec.pluginRef(),
                        spec.inputMappings(),
                        spec.outputMappings(),
                        EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES,
                        spec.params() != null && !spec.params().isEmpty() ? spec.params() : Map.of(),
                        null, null, null
                );
            } else {
                raw = new ExecutionTreeNode(
                        id,
                        displayName,
                        NodeType.PLUGIN,
                        List.of(),
                        "PLUGIN",
                        spec.pluginRef(),
                        spec.inputMappings(),
                        spec.outputMappings(),
                        EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES, EMPTY_FEATURES,
                        Map.of(),
                        null, null, null
                );
            }
            ExecutionTreeNode enriched = pipelineFeatureContext != null
                    ? nodeFeatureEnricher.enrich(raw, pipelineFeatureContext)
                    : raw;
            nodes.add(enriched);
            expandedNodes.add(new ExpandedNode(enriched.getId(), enriched.getDisplayName(), enriched.getPluginRef()));
        }
        if (!nodes.isEmpty()) {
            tree.attachChildren(parentId, nodes);
            tree.markPlannerExpanded(parentId);
            expansionState.incrementPlannerInvocations();
        }
        return new ExpansionResult(expandedNodes, children);
    }

    private ExpansionResult buildResultFromExistingChildren(String parentId, List<NodeSpec> childSpecs) {
        var state = tree.getNode(parentId);
        if (state == null) return new ExpansionResult(List.of(), childSpecs);
        List<ExpandedNode> existing = new ArrayList<>();
        for (String childId : state.getChildIds()) {
            ExecutionTreeNode def = tree.getDefinition(childId);
            if (def != null) {
                existing.add(new ExpandedNode(def.getId(), def.getDisplayName(), def.getPluginRef()));
            }
        }
        return new ExpansionResult(existing, childSpecs);
    }
}
