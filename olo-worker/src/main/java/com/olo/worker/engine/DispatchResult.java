package com.olo.worker.engine;

import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.List;

/**
 * Result of dispatching a node: optional result value and/or list of children to run next.
 * Containers return children to run; leaves return a result. Executor adds childrenToRun to the queue.
 */
public final class DispatchResult {

    private final Object result;
    private final List<ExecutionTreeNode> childrenToRun;

    private DispatchResult(Object result, List<ExecutionTreeNode> childrenToRun) {
        this.result = result;
        this.childrenToRun = childrenToRun != null ? List.copyOf(childrenToRun) : null;
    }

    public static DispatchResult withResult(Object result) {
        return new DispatchResult(result, null);
    }

    public static DispatchResult withChildren(List<ExecutionTreeNode> childrenToRun) {
        return new DispatchResult(null, childrenToRun);
    }

    public static DispatchResult withResultAndChildren(Object result, List<ExecutionTreeNode> childrenToRun) {
        return new DispatchResult(result, childrenToRun);
    }

    public Object getResult() {
        return result;
    }

    public List<ExecutionTreeNode> getChildrenToRun() {
        return childrenToRun;
    }
}
