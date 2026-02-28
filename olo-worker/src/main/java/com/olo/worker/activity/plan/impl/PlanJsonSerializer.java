package com.olo.worker.activity.plan.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.worker.engine.ExecutionPlanBuilder;
import com.olo.worker.engine.VariableEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Serialize execution plan (linear or parallel) to JSON. */
final class PlanJsonSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String buildPlanJson(PlanContextResolver.ResolvedPlanContext ctx, String workflowInputJson,
                                List<ExecutionPlanBuilder.PlanEntry> linearPlan,
                                ExecutionPlanBuilder.PlanWithParallelResult parallelResult) throws Exception {
        VariableEngine initialEngine = new VariableEngine(ctx.pipeline, ctx.inputValues);
        String initialVariableMapJson = MAPPER.writeValueAsString(initialEngine.getExportMap());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linear", true);
        out.put("runId", UUID.randomUUID().toString());
        out.put("configJson", MAPPER.writeValueAsString(ctx.config));
        out.put("pipelineName", ctx.pipeline.getName());
        out.put("queueName", ctx.effectiveQueue);
        out.put("workflowInputJson", workflowInputJson);
        out.put("initialVariableMapJson", initialVariableMapJson);
        if (parallelResult != null) {
            List<List<Map<String, Object>>> stepsData = new ArrayList<>();
            ExecutionTreeNode treeRoot = ctx.pipeline.getExecutionTree();
            for (List<ExecutionPlanBuilder.PlanEntry> step : parallelResult.getSteps()) {
                List<Map<String, Object>> stepNodes = new ArrayList<>();
                for (ExecutionPlanBuilder.PlanEntry e : step) {
                    ExecutionTreeNode node = ExecutionTreeNode.findNodeById(treeRoot, e.getNodeId());
                    List<String> outputVars = node == null || node.getOutputMappings() == null ? List.of()
                            : node.getOutputMappings().stream().map(ParameterMapping::getVariable).filter(Objects::nonNull).toList();
                    Map<String, Object> nodeData = new LinkedHashMap<>();
                    nodeData.put("activityType", e.getActivityType());
                    nodeData.put("nodeId", e.getNodeId());
                    nodeData.put("outputVariables", outputVars);
                    stepNodes.add(nodeData);
                }
                stepsData.add(stepNodes);
            }
            out.put("steps", stepsData);
            if (parallelResult.getTryCatchCatchStepIndex() != null && parallelResult.getTryCatchCatchStepIndex() >= 0) {
                Map<String, Object> tryCatchMeta = new LinkedHashMap<>();
                tryCatchMeta.put("catchStepIndex", parallelResult.getTryCatchCatchStepIndex());
                if (parallelResult.getTryCatchErrorVariable() != null) tryCatchMeta.put("errorVariable", parallelResult.getTryCatchErrorVariable());
                out.put("tryCatch", tryCatchMeta);
            }
        } else {
            List<Map<String, String>> nodes = new ArrayList<>();
            for (ExecutionPlanBuilder.PlanEntry e : linearPlan) {
                nodes.add(Map.of("activityType", e.getActivityType(), "nodeId", e.getNodeId()));
            }
            out.put("nodes", nodes);
        }
        return MAPPER.writeValueAsString(out);
    }
}
