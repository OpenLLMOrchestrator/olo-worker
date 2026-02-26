package com.olo.node;

/**
 * Contract for expanding a PLANNER node: worker validates, creates nodes (own ID policy),
 * attaches to tree (own mutation), and returns the result. Planner never touches the tree.
 * <p>
 * Implementation lives in the worker; planner only sees {@link DynamicNodeExpansionRequest}
 * and {@link ExpansionResult}.
 */
public interface DynamicNodeFactory {

    /**
     * Expands the given request: validates, creates nodes with worker-assigned IDs,
     * attaches children to the tree, and returns the expansion result.
     *
     * @param request expansion request (planner node id + semantic child specs)
     * @return result with expanded node descriptors and child specs (same order)
     */
    ExpansionResult expand(DynamicNodeExpansionRequest request);
}
