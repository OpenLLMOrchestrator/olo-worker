package com.olo.node;

import java.util.List;

/**
 * Request from the planner to expand a PLANNER node with children. The planner provides
 * only semantic descriptions ({@link NodeSpec}); it does not mutate the tree or construct nodes.
 */
public record DynamicNodeExpansionRequest(
        String plannerNodeId,
        List<NodeSpec> children
) {
    public DynamicNodeExpansionRequest {
        children = children != null ? List.copyOf(children) : List.of();
    }
}
