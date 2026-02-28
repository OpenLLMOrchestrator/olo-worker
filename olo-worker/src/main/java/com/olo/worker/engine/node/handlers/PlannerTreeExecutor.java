package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.node.DynamicNodeExpansionRequest;
import com.olo.node.DynamicNodeFactory;
import com.olo.node.ExpandedNode;
import com.olo.node.ExpansionResult;
import com.olo.node.NodeSpec;
import com.olo.node.PipelineFeatureContextImpl;
import com.olo.planner.SubtreeBuilder;
import com.olo.planner.SubtreeBuilderRegistry;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.DynamicNodeFactoryImpl;
import com.olo.worker.engine.node.ExpansionLimits;
import com.olo.worker.engine.node.ExpansionState;
import com.olo.worker.engine.node.NodeParams;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single responsibility: run planner tree expansion (model/interpret, subtree creator or parser, attach to tree).
 */
final class PlannerTreeExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlannerTreeExecutor.class);

    static Object executePlannerTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                     VariableEngine variableEngine, String queueName, RuntimeExecutionTree tree,
                                     ExpansionState expansionState, ExpansionLimits expansionLimits,
                                     HandlerContext ctx) {
        var pluginInvoker = ctx.getPluginInvoker();
        var nodeFeatureEnricher = ctx.getNodeFeatureEnricher();
        String modelPluginRef = NodeParams.paramString(node, "modelPluginRef");
        boolean interpretOnly = (modelPluginRef == null || modelPluginRef.isBlank());
        String parserName = NodeParams.paramString(node, "parser");
        if (parserName == null || parserName.isBlank()) parserName = NodeParams.paramString(node, "treeBuilder");
        if (parserName == null || parserName.isBlank()) parserName = "default";
        String subtreeCreatorPluginRef = NodeParams.paramString(node, "subtreeCreatorPluginRef");
        log.info("Executing PLANNER | nodeId={} | mode={} | modelPluginRef={} | parser={} | subtreeCreator={}",
                node.getId(), interpretOnly ? "interpretOnly" : "model", modelPluginRef, parserName,
                subtreeCreatorPluginRef != null && !subtreeCreatorPluginRef.isBlank() ? subtreeCreatorPluginRef : "-");
        log.info("PLANNER step 1 | nodeId={} | entry | tree present", node.getId());
        String planInputVariable = NodeParams.paramString(node, "planInputVariable");
        if (planInputVariable == null || planInputVariable.isBlank()) planInputVariable = "__planner_result";
        log.info("PLANNER step 2 | nodeId={} | mode={} | modelPluginRef={} | planInputVariable={}", node.getId(), interpretOnly ? "interpretOnly" : "model", modelPluginRef, planInputVariable);

        String planResultJson = PlannerPlanResolver.resolvePlanResultJson(
                node, variableEngine, queueName, interpretOnly, planInputVariable, modelPluginRef, pluginInvoker);
        int planInputLen = planResultJson != null ? planResultJson.length() : 0;
        String planInputSnippet = planResultJson != null && planResultJson.length() > 500
                ? planResultJson.substring(0, 500) + "...[truncated]" : (planResultJson != null ? planResultJson : "");
        log.info("PLANNER step 4 | nodeId={} | plan input | length={} | snippet={}", node.getId(), planInputLen, planInputSnippet);

        if (subtreeCreatorPluginRef != null && !subtreeCreatorPluginRef.isBlank()) {
            log.info("PLANNER step 5a | nodeId={} | using subtreeCreatorPluginRef={}", node.getId(), subtreeCreatorPluginRef);
            Map<String, Object> creatorInput = Map.of("planText", planResultJson != null ? planResultJson : "");
            Map<String, Object> creatorOutput = pluginInvoker.invokeWithInputMap(subtreeCreatorPluginRef, creatorInput);
            @SuppressWarnings("unchecked")
            Map<String, Object> variablesToInject = (Map<String, Object>) creatorOutput.get("variablesToInject");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) creatorOutput.get("steps");
            if (variablesToInject != null) {
                for (Map.Entry<String, Object> e : variablesToInject.entrySet()) variableEngine.put(e.getKey(), e.getValue());
            }
            if (steps != null && !steps.isEmpty()) {
                for (int i = 0; i < steps.size(); i++) {
                    Map<String, Object> step = steps.get(i);
                    if (step != null && step.containsKey("prompt")) variableEngine.put("__planner_step_" + i + "_prompt", step.get("prompt"));
                }
                PipelineFeatureContextImpl featureContext = new PipelineFeatureContextImpl(pipeline.getScope(), queueName);
                List<NodeSpec> specs = PlannerCreatorSteps.nodeSpecsFromCreatorSteps(steps);
                DynamicNodeFactory factory = new DynamicNodeFactoryImpl(tree, featureContext, nodeFeatureEnricher, expansionLimits, expansionState);
                factory.expand(new DynamicNodeExpansionRequest(node.getId(), specs));
                log.info("PLANNER step 6a | nodeId={} | expand from creator | count={} | stepRefs={}", node.getId(), specs.size(),
                        specs.stream().map(NodeSpec::pluginRef).filter(Objects::nonNull).toList());
            }
            log.info("PLANNER step 8a | nodeId={} | exit (subtreeCreator path)", node.getId());
            return null;
        }

        log.info("PLANNER step 5b | nodeId={} | using parser (no subtreeCreator)", node.getId());
        SubtreeBuilder builder = SubtreeBuilderRegistry.get(parserName);
        if (builder == null) {
            log.warn("PLANNER node {}: no parser for '{}'", node.getId(), parserName);
            return null;
        }
        PipelineFeatureContextImpl featureContext = new PipelineFeatureContextImpl(pipeline.getScope(), queueName);
        DynamicNodeFactory factory = new DynamicNodeFactoryImpl(tree, featureContext, nodeFeatureEnricher, expansionLimits, expansionState);
        log.info("PLANNER step 6b | nodeId={} | parser={} | calling builder.buildExpansion", node.getId(), parserName);
        SubtreeBuilder.ExpansionBuildResult expansionResult = builder.buildExpansion(planResultJson, node.getId());
        List<NodeSpec> requestedSpecs = expansionResult.expansionRequest().children();
        if (requestedSpecs == null || requestedSpecs.isEmpty()) {
            log.warn("Planner could not add tree: parser returned no steps. nodeId={} | planLength={}", node.getId(), planResultJson != null ? planResultJson.length() : 0);
        }
        for (Map.Entry<String, Object> e : expansionResult.variablesToInject().entrySet()) {
            variableEngine.put(e.getKey(), e.getValue());
        }
        ExpansionResult expanded = factory.expand(expansionResult.expansionRequest());
        if (!expanded.expandedNodes().isEmpty()) {
            log.info("PLANNER step 7b | nodeId={} | expand done | parser={} | stepsCount={} | stepRefs={}", node.getId(), parserName, expanded.expandedNodes().size(),
                    expanded.expandedNodes().stream().map(ExpandedNode::pluginRef).filter(Objects::nonNull).toList());
        } else {
            log.warn("PLANNER could not add tree: no nodes attached. nodeId={} | parser={} | requestedSpecsCount={}",
                    node.getId(), parserName, requestedSpecs != null ? requestedSpecs.size() : 0);
        }
        log.info("PLANNER step 9 | nodeId={} | exit", node.getId());
        return null;
    }
}
