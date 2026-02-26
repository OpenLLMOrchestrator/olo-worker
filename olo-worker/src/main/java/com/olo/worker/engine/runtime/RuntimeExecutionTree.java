package com.olo.worker.engine.runtime;

import com.olo.executiontree.tree.ExecutionTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable runtime tree: single source of truth for execution, debugger, and UI.
 * Built from static definition; planner adds nodes via {@link #attachChildren} only.
 * Dispatcher has no special case for planner — it just dispatches; expansion is inside planner execution.
 * <p>
 * For Temporal: this tree must be workflow-owned (in-memory for the run). If execution were split
 * across multiple activities, the tree would need to live in workflow state and be passed to/from activities
 * so the workflow "sees" new nodes. Currently the full loop runs inside one activity so the tree stays in memory.
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

    /** Attach planner-generated nodes as children of the given parent. No special handling in dispatcher. */
    public void attachChildren(String parentNodeId, List<ExecutionTreeNode> definitions) {
        if (parentNodeId == null || definitions == null || definitions.isEmpty()) {
            if (log.isInfoEnabled() && parentNodeId != null) {
                log.info("Tree attachChildren skip | parentId={} | definitions null or empty", parentNodeId);
            }
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("Tree attachChildren start | parentId={} | definitionsCount={}", parentNodeId, definitions.size());
        }
        RuntimeNodeState parent = nodesById.get(parentNodeId);
        if (parent == null) {
            if (log.isWarnEnabled()) log.warn("Tree attachChildren | parent not found | parentId={}", parentNodeId);
            return;
        }
        List<String> addedIds = new ArrayList<>();
        for (ExecutionTreeNode def : definitions) {
            if (def == null) continue;
            String id = def.getId();
            if (nodesById.containsKey(id)) continue;
            RuntimeNodeState state = new RuntimeNodeState(id, def, parentNodeId, true);
            nodesById.put(id, state);
            parent.addChildId(id);
            addedIds.add(id);
            if (log.isInfoEnabled()) {
                log.info("Tree node created | nodeId={} | parentId={} | type={} | displayName={} | pluginRef={}",
                        id, parentNodeId, def.getType(), def.getDisplayName(), def.getPluginRef());
            }
        }
        if (!addedIds.isEmpty() && log.isInfoEnabled()) {
            log.info("Tree attachChildren | parentId={} | added={} | childIds={}", parentNodeId, addedIds.size(), addedIds);
        }
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
        if (log.isInfoEnabled()) {
            log.info("Tree markCompleted | nodeId={}", nodeId);
        }
        RuntimeNodeState state = getNode(nodeId);
        if (state != null) state.setStatus(NodeStatus.COMPLETED);
    }

    public void markFailed(String nodeId) {
        if (log.isInfoEnabled()) {
            log.info("Tree markFailed | nodeId={}", nodeId);
        }
        RuntimeNodeState state = getNode(nodeId);
        if (state != null) state.setStatus(NodeStatus.FAILED);
    }

    public void markSkipped(String nodeId) {
        if (log.isInfoEnabled()) {
            log.info("Tree markSkipped | nodeId={}", nodeId);
        }
        RuntimeNodeState state = getNode(nodeId);
        if (state != null) state.setStatus(NodeStatus.SKIPPED);
    }

    /**
     * Returns the next node to execute: NOT_STARTED, parent COMPLETED, deterministic DFS order.
     * Returns null when no executable node remains.
     */
    public String findNextExecutable() {
        if (rootId == null) {
            if (log.isInfoEnabled()) log.info("Tree findNextExecutable | rootId null | return null");
            return null;
        }
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            RuntimeNodeState state = getNode(id);
            if (state == null) continue;
            if (state.getStatus() == NodeStatus.NOT_STARTED) {
                if (state.getParentId() == null) {
                    if (log.isInfoEnabled()) log.info("Tree findNextExecutable | found root | nodeId={}", id);
                    return id;
                }
                RuntimeNodeState parent = getNode(state.getParentId());
                if (parent != null && parent.getStatus() == NodeStatus.COMPLETED) {
                    if (log.isInfoEnabled()) log.info("Tree findNextExecutable | found | nodeId={} | parentId={} completed", id, state.getParentId());
                    return id;
                }
            }
            if (state.getStatus() == NodeStatus.COMPLETED || state.getStatus() == NodeStatus.SKIPPED) {
                List<String> childIds = state.getChildIds();
                for (int i = childIds.size() - 1; i >= 0; i--) {
                    stack.push(childIds.get(i));
                }
            }
        }
        if (log.isInfoEnabled()) log.info("Tree findNextExecutable | no executable node | return null");
        return null;
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

    /**
     * Depth of the node: root = 0, its children = 1, etc. (number of edges to root.)
     * Returns 0 if nodeId is null or not found.
     */
    public int getDepth(String nodeId) {
        if (nodeId == null) return 0;
        int depth = 0;
        String current = nodeId;
        while (current != null) {
            RuntimeNodeState state = nodesById.get(current);
            if (state == null) return 0;
            String parent = state.getParentId();
            if (parent == null) break;
            depth++;
            current = parent;
        }
        return depth;
    }
}
