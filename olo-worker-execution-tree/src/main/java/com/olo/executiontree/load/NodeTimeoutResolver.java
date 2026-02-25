package com.olo.executiontree.load;

import com.olo.executiontree.defaults.ActivityDefaultTimeouts;
import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves activity timeout properties for every execution tree node at bootstrap.
 * Precedence: current node → parent → … → root → global default.
 * Result is a map nodeId → final applicable {@link ActivityDefaultTimeouts}.
 */
public final class NodeTimeoutResolver {

    private NodeTimeoutResolver() {
    }

    /**
     * Resolves timeouts for all nodes in the tree. Each node gets
     * scheduleToStartSeconds, startToCloseSeconds, scheduleToCloseSeconds with
     * precedence: node's value if set, else parent's resolved value, else global default.
     *
     * @param root          root of the execution tree
     * @param globalDefault global default timeouts (e.g. from executionDefaults.activity.defaultTimeouts); null → {@link ActivityDefaultTimeouts#GLOBAL_DEFAULT}
     * @return map of node id → resolved timeouts (mutable; insert order by tree traversal)
     */
    public static Map<String, ActivityDefaultTimeouts> resolve(ExecutionTreeNode root, ActivityDefaultTimeouts globalDefault) {
        Map<String, ActivityDefaultTimeouts> out = new LinkedHashMap<>();
        ActivityDefaultTimeouts fallback = globalDefault != null ? globalDefault : ActivityDefaultTimeouts.GLOBAL_DEFAULT;
        resolveRec(root, fallback, out);
        return out;
    }

    private static void resolveRec(ExecutionTreeNode node, ActivityDefaultTimeouts parentResolved,
                                   Map<String, ActivityDefaultTimeouts> out) {
        if (node == null) return;
        ActivityDefaultTimeouts resolved = ActivityDefaultTimeouts.resolve(
                node.getScheduleToStartSeconds(),
                node.getStartToCloseSeconds(),
                node.getScheduleToCloseSeconds(),
                parentResolved);
        String id = node.getId();
        if (id != null && !id.isBlank()) {
            out.put(id, resolved);
        }
        ActivityDefaultTimeouts forChildren = resolved;
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) {
                resolveRec(child, forChildren, out);
            }
        }
    }
}
