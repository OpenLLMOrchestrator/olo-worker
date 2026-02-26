package com.olo.planner;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.DynamicNodeExpansionRequest;
import com.olo.node.PipelineFeatureContext;

import java.util.List;
import java.util.Map;

/**
 * Contract: builds a subtree from planner output text. Prefer {@link #buildExpansion} so the
 * planner only provides semantic descriptions ({@link com.olo.node.NodeSpec}); the worker owns
 * tree mutation via {@link com.olo.node.DynamicNodeFactory#expand}.
 * <p>
 * Implementations are registered by planner modules; the worker resolves by name via
 * {@link SubtreeBuilderRegistry} and does not contain parser logic.
 */
public interface SubtreeBuilder {

    /**
     * Parses the planner output and returns an expansion request (semantic child specs) plus
     * variables to inject. The worker calls {@link com.olo.node.DynamicNodeFactory#expand}
     * with this request; the planner does not mutate the tree or construct nodes.
     *
     * @param plannerOutputText raw planner output (e.g. JSON array of steps)
     * @param plannerNodeId     id of the PLANNER node that will receive the children (worker attaches by this id)
     * @return expansion request and variables to inject; never null
     */
    default ExpansionBuildResult buildExpansion(String plannerOutputText, String plannerNodeId) {
        return new ExpansionBuildResult(
                new DynamicNodeExpansionRequest(plannerNodeId != null ? plannerNodeId : "", List.of()),
                Map.of());
    }

    /**
     * Legacy: parses and builds an ordered list of nodes plus variables to inject.
     * Prefer {@link #buildExpansion} so the planner does not construct nodes and the worker owns tree mutation.
     *
     * @param plannerOutputText raw planner output (e.g. JSON array of steps)
     * @return result with nodes and variables to inject; never null
     * @deprecated Use {@link #buildExpansion} and {@link com.olo.node.DynamicNodeFactory#expand} instead.
     */
    @Deprecated
    BuildResult build(String plannerOutputText);

    /**
     * Legacy: builds fully designed nodes via builder. Prefer {@link #buildExpansion}.
     *
     * @param plannerOutputText raw planner output
     * @param nodeBuilder       builder for fully designed nodes; may be null
     * @param context           pipeline/queue context; may be null
     * @return result with nodes and variables to inject; never null
     * @deprecated Use {@link #buildExpansion} and worker-owned expansion instead.
     */
    @Deprecated
    default BuildResult build(String plannerOutputText, DynamicNodeBuilder nodeBuilder, PipelineFeatureContext context) {
        return build(plannerOutputText);
    }

    /** Result for expansion-only path: request (semantic specs) + variables. Planner does not see tree or nodes. */
    record ExpansionBuildResult(DynamicNodeExpansionRequest expansionRequest, Map<String, Object> variablesToInject) {
        public ExpansionBuildResult {
            expansionRequest = expansionRequest != null ? expansionRequest : new DynamicNodeExpansionRequest("", List.of());
            variablesToInject = variablesToInject != null ? Map.copyOf(variablesToInject) : Map.of();
        }
    }

    /**
     * Legacy result: nodes to run and variables. Prefer {@link ExpansionBuildResult} with {@link #buildExpansion}.
     * @deprecated Planner should not construct or return {@link ExecutionTreeNode}; use expansion contract instead.
     */
    @Deprecated
    record BuildResult(List<ExecutionTreeNode> nodes, Map<String, Object> variablesToInject) {
        public BuildResult {
            nodes = nodes != null ? List.copyOf(nodes) : List.of();
            variablesToInject = variablesToInject != null ? Map.copyOf(variablesToInject) : Map.of();
        }
    }
}
