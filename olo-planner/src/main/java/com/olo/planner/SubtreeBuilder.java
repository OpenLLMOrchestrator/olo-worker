package com.olo.planner;

import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.List;
import java.util.Map;

/**
 * Contract: builds a subtree (list of nodes + variables to inject) from planner output text.
 * Implementations are registered by planner modules; the worker resolves by name via
 * {@link SubtreeBuilderRegistry} and does not contain parser logic.
 */
public interface SubtreeBuilder {

    /**
     * Parses the planner output and builds an ordered list of nodes plus variables to inject.
     *
     * @param plannerOutputText raw planner output (e.g. JSON array of steps)
     * @return result with nodes and variables to inject; never null
     */
    BuildResult build(String plannerOutputText);

    /** Result of building a subtree: nodes to run and variables to put into the engine before running. */
    record BuildResult(List<ExecutionTreeNode> nodes, Map<String, Object> variablesToInject) {
        public BuildResult {
            nodes = nodes != null ? List.copyOf(nodes) : List.of();
            variablesToInject = variablesToInject != null ? Map.copyOf(variablesToInject) : Map.of();
        }
    }
}
