package com.olo.worker.engine.runtime;

import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful runtime view of one node: definition (immutable) + parent, children, status.
 * Tree is the single source of truth; dispatcher reads only from this.
 */
public final class RuntimeNodeState {

    private final String nodeId;
    private final ExecutionTreeNode definition;
    private final String parentId;
    private final List<String> childIds;
    private volatile NodeStatus status;
    private final boolean dynamic;

    public RuntimeNodeState(String nodeId, ExecutionTreeNode definition, String parentId, boolean dynamic) {
        this.nodeId = nodeId;
        this.definition = definition;
        this.parentId = parentId;
        this.childIds = new ArrayList<>();
        this.status = NodeStatus.NOT_STARTED;
        this.dynamic = dynamic;
    }

    public String getNodeId() {
        return nodeId;
    }

    public ExecutionTreeNode getDefinition() {
        return definition;
    }

    public String getParentId() {
        return parentId;
    }

    /** Mutable list of child node ids (static + dynamically attached). */
    public List<String> getChildIds() {
        return childIds;
    }

    public void addChildId(String id) {
        if (id != null && !id.isEmpty()) childIds.add(id);
    }

    public NodeStatus getStatus() {
        return status;
    }

    public void setStatus(NodeStatus status) {
        this.status = status != null ? status : NodeStatus.NOT_STARTED;
    }

    public boolean isDynamic() {
        return dynamic;
    }
}
