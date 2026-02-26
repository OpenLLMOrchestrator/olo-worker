package com.olo.node;

import java.util.List;

/**
 * Result of expanding a PLANNER node. The planner receives validated, fully constructed
 * node descriptors (IDs assigned by worker). Does not expose ExecutionTreeNode or tree internals.
 */
public record ExpansionResult(
        List<ExpandedNode> expandedNodes,
        List<NodeSpec> childSpecs
) {
    public ExpansionResult {
        expandedNodes = expandedNodes != null ? List.copyOf(expandedNodes) : List.of();
        childSpecs = childSpecs != null ? List.copyOf(childSpecs) : List.of();
    }
}
