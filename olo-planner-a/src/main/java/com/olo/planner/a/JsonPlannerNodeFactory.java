package com.olo.planner.a;

import com.fasterxml.jackson.databind.JsonNode;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.node.DynamicNodeSpec;
import com.olo.node.NodeSpec;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single responsibility: build NodeSpec / ExecutionTreeNode / DynamicNodeSpec objects
 * from normalized JsonPlannerStepInfo + index.
 */
final class JsonPlannerNodeFactory {

    NodeSpec toNodeSpec(JsonPlannerStepInfo info, int index, Map<String, Object> variablesToInject) {
        if (info.planner()) {
            return NodeSpec.planner(info.displayName(), info.toolId(), info.plannerParams());
        }
        PromptVars vars = promptVars(info.input(), index, variablesToInject);
        List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", vars.promptVar()));
        List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", vars.responseVar()));
        return NodeSpec.plugin("step-" + index + "-" + info.toolId(), info.toolId(), inputMappings, outputMappings);
    }

    ExecutionTreeNode toExecutionNode(JsonPlannerStepInfo info, int index, Map<String, Object> variablesToInject) {
        PromptVars vars = promptVars(info.input(), index, variablesToInject);
        List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", vars.promptVar()));
        List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", vars.responseVar()));
        return new ExecutionTreeNode(
                UUID.randomUUID().toString(),
                "step-" + index + "-" + info.toolId(),
                NodeType.PLUGIN,
                List.of(),
                "PLUGIN",
                info.toolId(),
                inputMappings,
                outputMappings,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                null,
                null,
                null
        );
    }

    DynamicNodeSpec toDynamicSpec(JsonPlannerStepInfo info, int index, Map<String, Object> variablesToInject) {
        PromptVars vars = promptVars(info.input(), index, variablesToInject);
        List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", vars.promptVar()));
        List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", vars.responseVar()));
        return new DynamicNodeSpec(
                UUID.randomUUID().toString(),
                "step-" + index + "-" + info.toolId(),
                info.toolId(),
                inputMappings,
                outputMappings
        );
    }

    private PromptVars promptVars(JsonNode input, int index, Map<String, Object> variablesToInject) {
        String promptVar = "__planner_step_" + index + "_prompt";
        String responseVar = "__planner_step_" + index + "_response";
        String prompt = "";
        if (input != null && input.has("prompt")) {
            prompt = input.get("prompt").asText("");
        }
        variablesToInject.put(promptVar, prompt);
        return new PromptVars(promptVar, responseVar);
    }

    private record PromptVars(String promptVar, String responseVar) {}
}

