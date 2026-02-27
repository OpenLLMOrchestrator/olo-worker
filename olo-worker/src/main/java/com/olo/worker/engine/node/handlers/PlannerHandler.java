package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.node.DynamicNodeExpansionRequest;
import com.olo.node.DynamicNodeFactory;
import com.olo.node.ExpandedNode;
import com.olo.node.ExpansionResult;
import com.olo.node.NodeSpec;
import com.olo.node.PipelineFeatureContextImpl;
import com.olo.planner.PlannerContract;
import com.olo.planner.PromptTemplateProvider;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single responsibility: execute PLANNER nodes (tree expansion and runPlannerReturnSteps).
 */
public final class PlannerHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(PlannerHandler.class);

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
        var pluginInvoker = ctx.getPluginInvoker();
        var nodeFeatureEnricher = ctx.getNodeFeatureEnricher();

        String modelPluginRef = NodeParams.paramString(node, "modelPluginRef");
        boolean interpretOnly = (modelPluginRef == null || modelPluginRef.isBlank());
        String parserName = NodeParams.paramString(node, "parser");
        if (parserName == null || parserName.isBlank()) parserName = NodeParams.paramString(node, "treeBuilder");
        if (parserName == null || parserName.isBlank()) parserName = "default";
        String subtreeCreatorPluginRef = NodeParams.paramString(node, "subtreeCreatorPluginRef");
        log.info("Executing PLANNER | nodeId={} | mode={} | modelPluginRef={} | parser={} | subtreeCreator={}",
                node.getId(), interpretOnly ? "interpretOnly" : "model", modelPluginRef, parserName, subtreeCreatorPluginRef != null && !subtreeCreatorPluginRef.isBlank() ? subtreeCreatorPluginRef : "-");
        log.info("PLANNER step 1 | nodeId={} | entry | tree present", node.getId());
        String planInputVariable = NodeParams.paramString(node, "planInputVariable");
        if (planInputVariable == null || planInputVariable.isBlank()) planInputVariable = "__planner_result";
        log.info("PLANNER step 2 | nodeId={} | mode={} | modelPluginRef={} | planInputVariable={}", node.getId(), interpretOnly ? "interpretOnly" : "model", modelPluginRef, planInputVariable);

        String planResultJson = resolvePlanResultJson(node, pipeline, variableEngine, queueName, interpretOnly, planInputVariable, modelPluginRef, pluginInvoker);

        int planInputLen = planResultJson != null ? planResultJson.length() : 0;
        String planInputSnippet = planResultJson != null && planResultJson.length() > 500
                ? planResultJson.substring(0, 500) + "...[truncated]"
                : (planResultJson != null ? planResultJson : "");
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
                PipelineFeatureContextImpl featureContext = new PipelineFeatureContextImpl(pipeline.getScope(), queueName);
                List<NodeSpec> specs = nodeSpecsFromCreatorSteps(steps);
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
            log.warn("Planner could not add tree: parser returned no steps. nodeId={} | planLength={} | Check model output is a JSON array of objects with \"toolId\" and \"input\". Example: [{\"toolId\":\"ECHO_TOOL\",\"input\":{\"prompt\":\"...\"}}]",
                    node.getId(), planResultJson != null ? planResultJson.length() : 0);
        }
        for (Map.Entry<String, Object> e : expansionResult.variablesToInject().entrySet()) {
            variableEngine.put(e.getKey(), e.getValue());
        }
        ExpansionResult expanded = factory.expand(expansionResult.expansionRequest());
        if (!expanded.expandedNodes().isEmpty()) {
            log.info("PLANNER step 7b | nodeId={} | expand done | parser={} | stepsCount={} | stepRefs={}", node.getId(), parserName, expanded.expandedNodes().size(),
                    expanded.expandedNodes().stream().map(ExpandedNode::pluginRef).filter(Objects::nonNull).toList());
        } else {
            log.warn("PLANNER could not add tree: no nodes attached. nodeId={} | parser={} | requestedSpecsCount={} (possible causes: planner already expanded, expansion limits hit, or parser returned no valid steps)",
                    node.getId(), parserName, requestedSpecs != null ? requestedSpecs.size() : 0);
        }
        log.info("PLANNER step 9 | nodeId={} | exit", node.getId());
        return null;
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

        String planResultJson = resolvePlanResultJson(node, pipeline, variableEngine, queueName, interpretOnly, planInputVariable, modelPluginRef, pluginInvoker);

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
                return buildNodesFromCreatorSteps(steps, new PipelineFeatureContextImpl(pipeline.getScope(), queueName), nodeFeatureEnricher);
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

    private static String resolvePlanResultJson(ExecutionTreeNode node, PipelineDefinition pipeline,
                                               VariableEngine variableEngine, String queueName,
                                               boolean interpretOnly, String planInputVariable, String modelPluginRef,
                                               com.olo.worker.engine.PluginInvoker pluginInvoker) {
        if (!interpretOnly) {
            log.info("PLANNER step 3a | nodeId={} | resolving template and userQuery", node.getId());
            String resultVariable = NodeParams.paramString(node, "resultVariable");
            if (resultVariable == null || resultVariable.isBlank()) resultVariable = "__planner_result";
            String userQueryVariable = NodeParams.paramString(node, "userQueryVariable");
            if (userQueryVariable == null || userQueryVariable.isBlank()) userQueryVariable = "userQuery";
            String promptVar = "__planner_prompt";
            String template = PromptTemplateProvider.getTemplate(queueName);
            if (template == null || template.isBlank()) template = PromptTemplateProvider.getTemplate("default");
            if (template == null || template.isBlank()) {
                log.warn("PLANNER node {}: no template for queue {} or default", node.getId(), queueName);
                return "";
            }
            Object userQueryObj = variableEngine.get(userQueryVariable);
            String userQuery = userQueryObj != null ? userQueryObj.toString() : "";
            String filledPrompt = template.replace(PlannerContract.USER_QUERY_PLACEHOLDER, userQuery);
            variableEngine.put(promptVar, filledPrompt);
            if (log.isInfoEnabled()) {
                int maxLog = 400;
                String promptSnippet = filledPrompt.length() > maxLog ? filledPrompt.substring(0, maxLog) + "...[truncated]" : filledPrompt;
                log.info("PLANNER step 3b | nodeId={} | invoking model | pluginRef={} | userQuery length={} | filledPrompt length={} | snippet={}", node.getId(), modelPluginRef, userQuery.length(), filledPrompt.length(), promptSnippet);
            }
            Map<String, String> inputVarToParam = new LinkedHashMap<>();
            inputVarToParam.put(promptVar, "prompt");
            Map<String, String> outputParamToVar = new LinkedHashMap<>();
            outputParamToVar.put("responseText", resultVariable);
            pluginInvoker.invokeWithVariableMapping(modelPluginRef, inputVarToParam, outputParamToVar, variableEngine);
            Object planResultObj = variableEngine.get(resultVariable);
            String planResultJson = planResultObj != null ? planResultObj.toString() : "";
            log.info("PLANNER step 3c | nodeId={} | model returned | result length={}", node.getId(), planResultJson != null ? planResultJson.length() : 0);
            return planResultJson;
        }
        log.info("PLANNER step 3d | nodeId={} | reading plan from variable {}", node.getId(), planInputVariable);
        Object planResultObj = variableEngine.get(planInputVariable);
        return planResultObj != null ? planResultObj.toString() : "";
    }

    private static List<NodeSpec> nodeSpecsFromCreatorSteps(List<Map<String, Object>> steps) {
        List<NodeSpec> specs = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String pluginRef = step != null && step.get("pluginRef") != null ? step.get("pluginRef").toString().trim() : null;
            if (pluginRef == null || pluginRef.isBlank()) continue;
            String promptVar = "__planner_step_" + i + "_prompt";
            String responseVar = "__planner_step_" + i + "_response";
            List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", promptVar));
            List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", responseVar));
            specs.add(NodeSpec.plugin("step-" + i + "-" + pluginRef, pluginRef, inputMappings, outputMappings));
        }
        return specs;
    }

    private static List<ExecutionTreeNode> buildNodesFromCreatorSteps(List<Map<String, Object>> steps,
                                                                      PipelineFeatureContextImpl featureContext,
                                                                      com.olo.node.NodeFeatureEnricher nodeFeatureEnricher) {
        List<String> emptyStrList = List.of();
        List<ExecutionTreeNode> nodes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String pluginRef = step != null && step.get("pluginRef") != null ? step.get("pluginRef").toString().trim() : null;
            if (pluginRef == null || pluginRef.isBlank()) continue;
            String promptVar = "__planner_step_" + i + "_prompt";
            String responseVar = "__planner_step_" + i + "_response";
            List<ParameterMapping> inputMappings = List.of(new ParameterMapping("prompt", promptVar));
            List<ParameterMapping> outputMappings = List.of(new ParameterMapping("responseText", responseVar));
            ExecutionTreeNode child = new ExecutionTreeNode(
                    java.util.UUID.randomUUID().toString(),
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
