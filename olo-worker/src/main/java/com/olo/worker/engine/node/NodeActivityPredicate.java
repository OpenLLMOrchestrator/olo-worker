package com.olo.worker.engine.node;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;

import java.util.List;

/**
 * Single responsibility: determine whether a node is an "activity" node.
 * An activity node has no children (leaf) and a type that performs executable work;
 * empty containers (SEQUENCE, GROUP, IF, etc.) are not activity nodes.
 */
public final class NodeActivityPredicate {

    private NodeActivityPredicate() {
    }

    /**
     * True if this node is an activity node: no children (leaf) and type that performs executable work.
     * Excludes empty containers so they do not get plan entries or pre/post features.
     */
    public static boolean isActivityNode(ExecutionTreeNode node) {
        if (node == null) return false;
        List<ExecutionTreeNode> children = node.getChildren();
        if (children != null && !children.isEmpty()) return false;
        return isExecutableActivityType(node.getType());
    }

    /**
     * True if this node type represents executable work when the node is a leaf.
     */
    public static boolean isExecutableActivityType(NodeType type) {
        if (type == null) return false;
        return switch (type) {
            case PLUGIN, JOIN, EVENT_WAIT, LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION, UNKNOWN -> true;
            case SEQUENCE, GROUP, IF, SWITCH, CASE, ITERATOR, FORK, TRY_CATCH, RETRY, SUB_PIPELINE -> false;
        };
    }
}
