package com.olo.worker.engine.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Single responsibility: find next executable node (DFS) and compute depth for a runtime tree.
 */
final class RuntimeTreeTraversal {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTreeTraversal.class);

    private RuntimeTreeTraversal() {
    }

    /**
     * Returns the next node id to execute (NOT_STARTED, parent COMPLETED, DFS order), or null if none.
     */
    static String findNextExecutable(String rootId, Map<String, RuntimeNodeState> nodesById) {
        if (rootId == null) {
            if (log.isInfoEnabled()) log.info("Tree findNextExecutable | rootId null | return null");
            return null;
        }
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            RuntimeNodeState state = nodesById.get(id);
            if (state == null) continue;
            if (state.getStatus() == NodeStatus.NOT_STARTED) {
                if (state.getParentId() == null) {
                    if (log.isInfoEnabled()) log.info("Tree findNextExecutable | found root | nodeId={}", id);
                    return id;
                }
                RuntimeNodeState parent = nodesById.get(state.getParentId());
                if (parent != null && parent.getStatus() == NodeStatus.COMPLETED) {
                    if (log.isInfoEnabled()) log.info("Tree findNextExecutable | found | nodeId={} | parentId={} completed", id, state.getParentId());
                    return id;
                }
            }
            if (state.getStatus() == NodeStatus.COMPLETED || state.getStatus() == NodeStatus.SKIPPED) {
                for (int i = state.getChildIds().size() - 1; i >= 0; i--) {
                    stack.push(state.getChildIds().get(i));
                }
            }
        }
        if (log.isInfoEnabled()) log.info("Tree findNextExecutable | no executable node | return null");
        return null;
    }

    /** Depth of node (root = 0). Returns 0 if nodeId is null or not found. */
    static int getDepth(String nodeId, Map<String, RuntimeNodeState> nodesById) {
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
