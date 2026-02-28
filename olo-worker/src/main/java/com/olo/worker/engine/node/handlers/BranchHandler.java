package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ChildNodeRunner;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.node.NodeParams;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single responsibility: handle IF and SWITCH branching (including tree-mode variants).
 */
public final class BranchHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(BranchHandler.class);

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.IF, NodeType.SWITCH);
    }

    @Override
    public Object dispatch(ExecutionTreeNode node,
                           PipelineDefinition pipeline,
                           VariableEngine variableEngine,
                           String queueName,
                           ChildNodeRunner runChild,
                           ChildNodeRunner runChildSync,
                           HandlerContext ctx) {
        return switch (node.getType()) {
            case IF -> executeIf(node, pipeline, variableEngine, queueName, runChild);
            case SWITCH -> executeSwitch(node, pipeline, variableEngine, queueName, runChild);
            default -> null;
        };
    }

    @Override
    public Object dispatchWithTree(ExecutionTreeNode node,
                                   PipelineDefinition pipeline,
                                   VariableEngine variableEngine,
                                   String queueName,
                                   RuntimeExecutionTree tree,
                                   Consumer<String> subtreeRunner,
                                   ExpansionState expansionState,
                                   ExpansionLimits expansionLimits,
                                   HandlerContext ctx) {
        return switch (node.getType()) {
            case IF -> executeIfTree(node, variableEngine, tree);
            case SWITCH -> executeSwitchTree(node, variableEngine, tree);
            default -> null;
        };
    }

    private Object executeIf(ExecutionTreeNode node, PipelineDefinition pipeline,
                             VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String conditionVar = NodeParams.paramString(node, "conditionVariable");
        boolean condition = true;
        if (conditionVar != null && !conditionVar.isBlank()) {
            Object val = variableEngine.get(conditionVar);
            condition = NodeParams.isTruthy(val);
        }
        List<ExecutionTreeNode> children = node.getChildren();
        if (condition && !children.isEmpty()) {
            runChild.run(children.get(0), pipeline, variableEngine, queueName);
        } else if (!condition && children.size() > 1) {
            runChild.run(children.get(1), pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeSwitch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String switchVar = NodeParams.paramString(node, "switchVariable");
        if (switchVar == null || switchVar.isBlank()) {
            log.warn("SWITCH node {} missing switchVariable in params", node.getId());
            return null;
        }
        Object value = variableEngine.get(switchVar);
        for (ExecutionTreeNode child : node.getChildren()) {
            if (child.getType() != NodeType.CASE) continue;
            Object caseVal = child.getParams().get("caseValue");
            if (Objects.equals(value, caseVal) || (value != null && value.toString().equals(caseVal != null ? caseVal.toString() : null))) {
                runChild.run(child, pipeline, variableEngine, queueName);
                return null;
            }
        }
        log.debug("SWITCH node {} no matching CASE for value={}", node.getId(), value);
        return null;
    }

    private Object executeIfTree(ExecutionTreeNode node,
                                 VariableEngine variableEngine,
                                 RuntimeExecutionTree tree) {
        String conditionVar = NodeParams.paramString(node, "conditionVariable");
        boolean condition = true;
        if (conditionVar != null && !conditionVar.isBlank()) {
            Object val = variableEngine.get(conditionVar);
            condition = NodeParams.isTruthy(val);
        }
        List<String> childIds = tree.getNode(node.getId()) != null ? tree.getNode(node.getId()).getChildIds() : List.of();
        if (childIds.size() >= 2) {
            String toSkip = condition ? childIds.get(1) : childIds.get(0);
            tree.markSkipped(toSkip);
        }
        return null;
    }

    private Object executeSwitchTree(ExecutionTreeNode node,
                                     VariableEngine variableEngine,
                                     RuntimeExecutionTree tree) {
        String switchVar = NodeParams.paramString(node, "switchVariable");
        if (switchVar == null || switchVar.isBlank()) return null;
        Object value = variableEngine.get(switchVar);
        List<String> childIds = tree.getNode(node.getId()) != null ? tree.getNode(node.getId()).getChildIds() : List.of();
        for (String childId : childIds) {
            ExecutionTreeNode child = tree.getDefinition(childId);
            if (child == null || child.getType() != NodeType.CASE) continue;
            Object caseVal = child.getParams() != null ? child.getParams().get("caseValue") : null;
            if (Objects.equals(value, caseVal) || (value != null && value.toString().equals(caseVal != null ? caseVal.toString() : null))) {
                continue;
            }
            tree.markSkipped(childId);
        }
        return null;
    }
}

