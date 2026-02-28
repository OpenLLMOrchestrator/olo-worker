package com.olo.worker.engine.runtime;

import com.olo.executiontree.tree.ExecutionTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable runtime tree for execution. Built from static definition; planner adds nodes via {@link #attachChildren}.
 */
public final class RuntimeExecutionTree {

    private static final Logger log = LoggerFactory.getLogger(RuntimeExecutionTree.class);

    private final Map<String, RuntimeNodeState> nodesById = new LinkedHashMap<>();
    private final String rootId;
    /** Planner node ids that have already been expanded (idempotency guard for activity retry). */
    private final Set<String> expandedPlannerNodeIds = new HashSet<>();

    public RuntimeExecutionTree(ExecutionTreeNode staticRoot) {
        if (staticRoot == null) {
            this.rootId = null;
            return;
        }
        this.rootId = staticRoot.getId();
        buildFromStatic(staticRoot, null);
    }

    private void buildFromStatic(ExecutionTreeNode node, String parentId) {
        if (node == null) return;
        String id = node.getId();
        RuntimeNodeState state = new RuntimeNodeState(id, node, parentId, false);
        nodesById.put(id, state);
        List<ExecutionTreeNode> children = node.getChildren();
        if (children != null) {
            for (ExecutionTreeNode child : children) {
                if (child != null) {
                    state.addChildId(child.getId());
                    buildFromStatic(child, id);
                }
            }
        }
    }

    public String getRootId() {
        return rootId;
    }

    public RuntimeNodeState getNode(String nodeId) {
        return nodeId != null ? nodesById.get(nodeId) : null;
    }

    public ExecutionTreeNode getDefinition(String nodeId) {
        RuntimeNodeState state = getNode(nodeId);
        return state != null ? state.getDefinition() : null;
    }

    /** Attach planner-generated nodes as children of the given parent. */
    public void attachChildren(String parentNodeId, List<ExecutionTreeNode> definitions) {
        RuntimeTreeAttach.attach(nodesById, parentNodeId, definitions);
    }

    /**
     * Mark a planner node as already expanded. Call after {@link #attachChildren} for that parent
     * so retries do not attach duplicate children (idempotency guard).
     */
    public void markPlannerExpanded(String plannerNodeId) {
        if (plannerNodeId != null && !plannerNodeId.isBlank()) {
            expandedPlannerNodeIds.add(plannerNodeId);
            if (log.isDebugEnabled()) {
                log.debug("Tree markPlannerExpanded | plannerNodeId={}", plannerNodeId);
            }
        }
    }

    /** True if this planner node has already been expanded (e.g. on a prior attempt before activity retry). */
    public boolean hasPlannerExpanded(String plannerNodeId) {
        return plannerNodeId != null && expandedPlannerNodeIds.contains(plannerNodeId);
    }

    public void markCompleted(String nodeId) {
        if (log.isInfoEnabled()) log.info("Tree markCompleted | nodeId={}", nodeId);
        setStatus(nodeId, NodeStatus.COMPLETED);
    }

    public void markFailed(String nodeId) {
        if (log.isInfoEnabled()) log.info("Tree markFailed | nodeId={}", nodeId);
        setStatus(nodeId, NodeStatus.FAILED);
    }

    public void markSkipped(String nodeId) {
        if (log.isInfoEnabled()) log.info("Tree markSkipped | nodeId={}", nodeId);
        setStatus(nodeId, NodeStatus.SKIPPED);
    }

    private void setStatus(String nodeId, NodeStatus status) {
        RuntimeNodeState state = getNode(nodeId);
        if (state != null) state.setStatus(status);
    }

    /** Returns the next node to execute (NOT_STARTED, parent COMPLETED, DFS), or null when none. */
    public String findNextExecutable() {
        return RuntimeTreeTraversal.findNextExecutable(rootId, nodesById);
    }

    /** True if nodeId is ancestorId or a descendant of ancestorId. */
    public boolean isDescendant(String nodeId, String ancestorId) {
        if (nodeId == null || ancestorId == null) return false;
        if (nodeId.equals(ancestorId)) return true;
        RuntimeNodeState state = getNode(nodeId);
        if (state == null) return false;
        return isDescendant(state.getParentId(), ancestorId);
    }

    /** Sets node and all descendants to NOT_STARTED (e.g. for ITERATOR body re-run). */
    public void resetSubtreeToNotStarted(String nodeId) {
        RuntimeNodeState state = getNode(nodeId);
        if (state == null) return;
        state.setStatus(NodeStatus.NOT_STARTED);
        for (String childId : state.getChildIds()) {
            resetSubtreeToNotStarted(childId);
        }
    }

    public List<RuntimeNodeState> getAllNodes() {
        return new ArrayList<>(nodesById.values());
    }

    /** Total number of nodes in the tree (static + dynamically attached). */
    public int getTotalNodeCount() {
        return nodesById.size();
    }

    /** Depth of node: root = 0. Returns 0 if nodeId is null or not found. */
    public int getDepth(String nodeId) {
        return RuntimeTreeTraversal.getDepth(nodeId, nodesById);
    }
}
