package com.olo.node;

import com.olo.executiontree.tree.ExecutionTreeNode;

/**
 * Contract for building a fully designed dynamic node (e.g. planner step) with pipeline
 * and queue-based features attached. The planner requests a new node through this contract
 * and receives a node ready to attach to the existing tree.
 * <p>
 * Implementation is provided by bootstrap (or execution-tree); planner and worker depend
 * only on this protocol. The builder is obtained via {@link com.olo.bootstrap.WorkerBootstrapContext#getDynamicNodeBuilder()}.
 */
@FunctionalInterface
public interface DynamicNodeBuilder {

    /**
     * Builds a fully designed execution tree node from the spec, with all applicable
     * pipeline and queue features attached (e.g. scope features, debug when queue ends with -debug).
     *
     * @param spec    minimal node spec (id, displayName, pluginRef, input/output mappings)
     * @param context pipeline and queue context
     * @return node ready to attach to the tree; never null
     */
    ExecutionTreeNode buildNode(DynamicNodeSpec spec, PipelineFeatureContext context);
}
