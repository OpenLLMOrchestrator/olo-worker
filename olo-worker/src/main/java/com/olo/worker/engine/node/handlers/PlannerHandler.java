package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.node.PipelineFeatureContextImpl;
import com.olo.planner.SubtreeBuilder;
import com.olo.planner.SubtreeBuilderRegistry;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.node.NodeParams;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Single responsibility: execute PLANNER nodes (tree expansion and runPlannerReturnSteps).
 */
public final class PlannerHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(PlannerHandler.class);

    @Override
    public java.util.Set<NodeType> supportedTypes() {
        return java.util.Set.of(NodeType.PLANNER);
    }

    @Override
    public Object dispatchWithTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName,
                                   RuntimeExecutionTree tree,
                                   java.util.function.Consumer<String> subtreeRunner,
                                   ExpansionState expansionState, ExpansionLimits expansionLimits,
                                   HandlerContext ctx) {
        return executePlannerTree(node, pipeline, variableEngine, queueName, tree, expansionState, expansionLimits, ctx);
    }

    /** Tree-driven: run model/interpret, parse, attach children to tree. */
    public Object executePlannerTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                    VariableEngine variableEngine, String queueName, RuntimeExecutionTree tree,
                                    ExpansionState expansionState, ExpansionLimits expansionLimits,
                                    HandlerContext ctx) {
        return PlannerTreeExecutor.executePlannerTree(node, pipeline, variableEngine, queueName, tree,
                expansionState, expansionLimits, ctx);
    }

    /** Returns list of step nodes without attaching to tree (for per-step Temporal activities). */
    public List<ExecutionTreeNode> runPlannerReturnSteps(ExecutionTreeNode node, PipelineDefinition pipeline,
                                                         VariableEngine variableEngine, String queueName,
                                                         HandlerContext ctx) {
        String planInputVariable = NodeParams.paramString(node, "planInputVariable");
        if (planInputVariable == null || planInputVariable.isBlank()) planInputVariable = "__planner_result";
        String modelPluginRef = NodeParams.paramString(node, "modelPluginRef");
        boolean interpretOnly = (modelPluginRef == null || modelPluginRef.isBlank());
        var pluginInvoker = ctx.getPluginInvoker();
        var nodeFeatureEnricher = ctx.getNodeFeatureEnricher();
        var dynamicNodeBuilder = ctx.getDynamicNodeBuilder();

        String planResultJson = PlannerPlanResolver.resolvePlanResultJson(
                node, variableEngine, queueName, interpretOnly, planInputVariable, modelPluginRef, pluginInvoker);

        String subtreeCreatorPluginRef = NodeParams.paramString(node, "subtreeCreatorPluginRef");
        if (subtreeCreatorPluginRef != null && !subtreeCreatorPluginRef.isBlank()) {
            Map<String, Object> creatorInput = Map.of("planText", planResultJson != null ? planResultJson : "");
            Map<String, Object> creatorOutput = pluginInvoker.invokeWithInputMap(subtreeCreatorPluginRef, creatorInput);
            @SuppressWarnings("unchecked")
            Map<String, Object> variablesToInject = (Map<String, Object>) creatorOutput.get("variablesToInject");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) creatorOutput.get("steps");
            if (variablesToInject != null) {
                for (Map.Entry<String, Object> e : variablesToInject.entrySet()) {
                    variableEngine.put(e.getKey(), e.getValue());
                }
            }
            if (steps != null && !steps.isEmpty()) {
                for (int i = 0; i < steps.size(); i++) {
                    Map<String, Object> step = steps.get(i);
                    if (step != null && step.containsKey("prompt")) {
                        variableEngine.put("__planner_step_" + i + "_prompt", step.get("prompt"));
                    }
                }
                return PlannerCreatorSteps.buildNodesFromCreatorSteps(
                        steps, new PipelineFeatureContextImpl(pipeline.getScope(), queueName), nodeFeatureEnricher);
            }
            return List.of();
        }

        String parserName = NodeParams.paramString(node, "parser");
        if (parserName == null || parserName.isBlank()) parserName = NodeParams.paramString(node, "treeBuilder");
        if (parserName == null || parserName.isBlank()) parserName = "default";
        SubtreeBuilder builder = SubtreeBuilderRegistry.get(parserName);
        if (builder == null) {
            log.warn("PLANNER node {}: no parser for '{}'", node.getId(), parserName);
            return List.of();
        }
        PipelineFeatureContextImpl featureContext = new PipelineFeatureContextImpl(pipeline.getScope(), queueName);
        SubtreeBuilder.BuildResult buildResult = dynamicNodeBuilder != null
                ? builder.build(planResultJson, dynamicNodeBuilder, featureContext)
                : builder.build(planResultJson);
        for (Map.Entry<String, Object> e : buildResult.variablesToInject().entrySet()) {
            variableEngine.put(e.getKey(), e.getValue());
        }
        if (dynamicNodeBuilder != null) {
            return buildResult.nodes();
        }
        return buildResult.nodes().stream()
                .map(n -> nodeFeatureEnricher.enrich(n, featureContext))
                .toList();
    }

}
