package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.node.NodeFeatureEnricher;
import com.olo.node.NodeSpec;
import com.olo.node.PipelineFeatureContextImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single responsibility: build NodeSpec and ExecutionTreeNode instances from planner creator steps.
 */
final class PlannerCreatorSteps {

    private PlannerCreatorSteps() {
    }

    static List<NodeSpec> nodeSpecsFromCreatorSteps(List<Map<String, Object>> steps) {
        List<NodeSpec> specs = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String pluginRef = step != null && step.get("pluginRef") != null
                    ? step.get("pluginRef").toString().trim()
                    : null;
            if (pluginRef == null || pluginRef.isBlank()) continue;
            String promptVar = "__planner_step_" + i + "_prompt";
            String responseVar = "__planner_step_" + i + "_response";
            List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", promptVar));
            List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", responseVar));
            specs.add(NodeSpec.plugin("step-" + i + "-" + pluginRef, pluginRef, inputMappings, outputMappings));
        }
        return specs;
    }

    static List<ExecutionTreeNode> buildNodesFromCreatorSteps(List<Map<String, Object>> steps,
                                                              PipelineFeatureContextImpl featureContext,
                                                              NodeFeatureEnricher nodeFeatureEnricher) {
        List<String> emptyStrList = List.of();
        List<ExecutionTreeNode> nodes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String pluginRef = step != null && step.get("pluginRef") != null
                    ? step.get("pluginRef").toString().trim()
                    : null;
            if (pluginRef == null || pluginRef.isBlank()) continue;
            String promptVar = "__planner_step_" + i + "_prompt";
            String responseVar = "__planner_step_" + i + "_response";
            List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", promptVar));
            List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", responseVar));
            ExecutionTreeNode child = new ExecutionTreeNode(
                    UUID.randomUUID().toString(),
                    "step-" + i + "-" + pluginRef,
                    NodeType.PLUGIN,
                    List.of(),
                    "PLUGIN",
                    pluginRef,
                    inputMappings,
                    outputMappings,
                    emptyStrList, emptyStrList, emptyStrList, emptyStrList, emptyStrList, emptyStrList, emptyStrList, emptyStrList,
                    Map.of(),
                    null, null, null
            );
            nodes.add(nodeFeatureEnricher.enrich(child, featureContext));
        }
        return nodes;
    }
}

