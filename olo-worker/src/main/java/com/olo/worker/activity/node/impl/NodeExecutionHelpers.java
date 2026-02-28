package com.olo.worker.activity.node.impl;

import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.tree.ExecutionTreeNode;

import java.util.Map;

final class NodeExecutionHelpers {

    private NodeExecutionHelpers() {
    }

    static boolean isFirstNodeInPlan(Map<String, Object> plan, String nodeId) {
        return NodeExecutionPlanUtils.isFirstNodeInPlan(plan, nodeId);
    }

    static Map<String, Object> dynamicStepFromNode(ExecutionTreeNode n) {
        return NodeExecutionStepUtils.dynamicStepFromNode(n);
    }

    static ExecutionTreeNode resolveDynamicStep(String nodeId, String dynamicStepsJson) {
        return NodeExecutionStepUtils.resolveDynamicStep(nodeId, dynamicStepsJson);
    }

    static String buildPluginVersionsJson(PipelineConfiguration config) {
        return NodeExecutionPlanUtils.buildPluginVersionsJson(config);
    }
}