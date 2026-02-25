package com.olo.worker.engine.node;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.ledger.LedgerContext;
import com.olo.planner.PlannerContract;
import com.olo.planner.PromptTemplateProvider;
import com.olo.planner.SubtreeBuilder;
import com.olo.planner.SubtreeBuilderRegistry;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.runtime.RuntimeExecutionTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Single responsibility: dispatch execution by node type (SEQUENCE, IF, PLUGIN, etc.).
 * Uses ChildNodeRunner callbacks to run child nodes; does not run pre/post features.
 */
public final class NodeExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutionDispatcher.class);

    private final PluginInvoker pluginInvoker;
    private final PipelineConfiguration config;
    private final ExecutionType executionType;
    private final ExecutorService executor;
    private final String ledgerRunId;

    public NodeExecutionDispatcher(PluginInvoker pluginInvoker, PipelineConfiguration config,
                                   ExecutionType executionType, ExecutorService executor,
                                   String ledgerRunId) {
        this.pluginInvoker = pluginInvoker;
        this.config = config;
        this.executionType = executionType != null ? executionType : ExecutionType.SYNC;
        this.executor = executor;
        this.ledgerRunId = ledgerRunId;
    }

    /**
     * Execute this node's logic only; returns result (e.g. from PLUGIN). Does not run pre/post features.
     * Use runChild for async child execution, runChildSync for sync (e.g. inside FORK parallel tasks).
     */
    public Object dispatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                           VariableEngine variableEngine, String queueName,
                           ChildNodeRunner runChild, ChildNodeRunner runChildSync) {
        NodeType type = node.getType();
        if (type == null || type == NodeType.UNKNOWN) {
            log.debug("Node {} has null or unknown type; skipping", node.getId());
            return null;
        }
        return switch (type) {
            case SEQUENCE -> executeSequence(node, pipeline, variableEngine, queueName, runChild);
            case IF -> executeIf(node, pipeline, variableEngine, queueName, runChild);
            case SWITCH -> executeSwitch(node, pipeline, variableEngine, queueName, runChild);
            case ITERATOR -> executeIterator(node, pipeline, variableEngine, queueName, runChild);
            case FORK -> executeFork(node, pipeline, variableEngine, queueName, runChild, runChildSync);
            case JOIN -> executeJoin(node, pipeline, variableEngine, queueName, runChild);
            case GROUP -> executeSequence(node, pipeline, variableEngine, queueName, runChild);
            case PLUGIN -> pluginInvoker.invoke(node, variableEngine);
            case CASE -> executeCase(node, pipeline, variableEngine, queueName, runChild);
            case TRY_CATCH -> executeTryCatch(node, pipeline, variableEngine, queueName, runChild);
            case RETRY -> executeRetry(node, pipeline, variableEngine, queueName, runChild);
            case SUB_PIPELINE -> executeSubPipeline(node, pipeline, variableEngine, queueName, runChild);
            case EVENT_WAIT -> executeEventWait(node, variableEngine);
            case LLM_DECISION -> executeLlmDecision(node, variableEngine);
            case TOOL_ROUTER -> executeToolRouter(node, pipeline, variableEngine, queueName, runChild);
            case EVALUATION -> executeEvaluation(node, variableEngine);
            case REFLECTION -> executeReflection(node, variableEngine);
            case PLANNER -> throw new IllegalStateException(
                "PLANNER must be executed via tree (NodeExecutor.runWithTree). Single-node/recursive dispatch is not supported. nodeId=" + node.getId());
            case FILL_TEMPLATE -> executeFillTemplate(node, variableEngine, queueName);
            case UNKNOWN -> null;
        };
    }

    /**
     * Tree-driven dispatch: state transition only. No special handling for PLANNER.
     * Containers mutate tree (IF/SWITCH skip; PLANNER attachChildren) or no-op; leaves run logic.
     * PLANNER: expansion happens here only — we attach children and return; we do NOT run children inline.
     * The executor loop will pick new nodes via findNextExecutable(). subtreeRunner is for ITERATOR body only.
     */
    public Object dispatchWithTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName, RuntimeExecutionTree tree,
                                  Consumer<String> subtreeRunner) {
        if (tree == null) {
            throw new IllegalStateException(
                "Runtime tree is required for tree-driven execution. nodeId=" + node.getId() + " type=" + node.getType()
                    + ". Use NodeExecutor.runWithTree().");
        }
        NodeType type = node.getType();
        if (type == null || type == NodeType.UNKNOWN) return null;
        return switch (type) {
            case SEQUENCE, GROUP, CASE -> null;
            case IF -> executeIfTree(node, pipeline, variableEngine, tree);
            case SWITCH -> executeSwitchTree(node, pipeline, variableEngine, tree);
            case PLANNER -> executePlannerTree(node, pipeline, variableEngine, queueName, tree);
            case PLUGIN -> pluginInvoker.invoke(node, variableEngine);
            case FILL_TEMPLATE -> executeFillTemplate(node, variableEngine, queueName);
            case EVENT_WAIT -> executeEventWait(node, variableEngine);
            case LLM_DECISION -> executeLlmDecision(node, variableEngine);
            case EVALUATION -> executeEvaluation(node, variableEngine);
            case REFLECTION -> executeReflection(node, variableEngine);
            case ITERATOR -> executeIteratorTree(node, pipeline, variableEngine, queueName, tree, subtreeRunner);
            case FORK -> null;
            case JOIN -> executeJoin(node, pipeline, variableEngine, queueName, (n, p, v, q) -> {});
            case TRY_CATCH -> null;
            case RETRY -> null;
            case SUB_PIPELINE -> null;
            case TOOL_ROUTER -> null;
            case UNKNOWN -> null;
        };
    }

    private Object executeIfTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                 VariableEngine variableEngine, RuntimeExecutionTree tree) {
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

    private Object executeSwitchTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                     VariableEngine variableEngine, RuntimeExecutionTree tree) {
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

    private Object executeIteratorTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                       VariableEngine variableEngine, String queueName, RuntimeExecutionTree tree,
                                       Consumer<String> subtreeRunner) {
        String collectionVar = NodeParams.paramString(node, "collectionVariable");
        String itemVar = NodeParams.paramString(node, "itemVariable");
        String indexVar = NodeParams.paramString(node, "indexVariable");
        if (collectionVar == null || itemVar == null) return null;
        Object coll = variableEngine.get(collectionVar);
        if (!(coll instanceof Collection)) return null;
        Collection<?> collection = (Collection<?>) coll;
        List<String> childIds = tree.getNode(node.getId()) != null ? tree.getNode(node.getId()).getChildIds() : List.of();
        if (childIds.isEmpty()) return null;
        String bodyId = childIds.get(0);
        int index = 0;
        for (Object item : collection) {
            if (indexVar != null) variableEngine.put(indexVar, index);
            variableEngine.put(itemVar, item != null ? item : "");
            if (index > 0) tree.resetSubtreeToNotStarted(bodyId);
            if (subtreeRunner != null) subtreeRunner.accept(bodyId);
            index++;
        }
        return null;
    }

    /** Planner node: run activity, parse plan, attach children to tree, return. Do not run children here. */
    private Object executePlannerTree(ExecutionTreeNode node, PipelineDefinition pipeline,
                                     VariableEngine variableEngine, String queueName, RuntimeExecutionTree tree) {
        log.info("PLANNER step 1 | nodeId={} | entry | tree present", node.getId());
        String planInputVariable = NodeParams.paramString(node, "planInputVariable");
        if (planInputVariable == null || planInputVariable.isBlank()) planInputVariable = "__planner_result";
        String modelPluginRef = NodeParams.paramString(node, "modelPluginRef");
        boolean interpretOnly = (modelPluginRef == null || modelPluginRef.isBlank());
        log.info("PLANNER step 2 | nodeId={} | mode={} | modelPluginRef={} | planInputVariable={}", node.getId(), interpretOnly ? "interpretOnly" : "model", modelPluginRef, planInputVariable);

        String planResultJson;
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
                return null;
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
            planResultJson = planResultObj != null ? planResultObj.toString() : "";
            log.info("PLANNER step 3c | nodeId={} | model returned | result length={}", node.getId(), planResultJson != null ? planResultJson.length() : 0);
        } else {
            log.info("PLANNER step 3d | nodeId={} | reading plan from variable {}", node.getId(), planInputVariable);
            Object planResultObj = variableEngine.get(planInputVariable);
            planResultJson = planResultObj != null ? planResultObj.toString() : "";
        }

        int planInputLen = planResultJson != null ? planResultJson.length() : 0;
        String planInputSnippet = planResultJson != null && planResultJson.length() > 500
                ? planResultJson.substring(0, 500) + "...[truncated]"
                : (planResultJson != null ? planResultJson : "");
        log.info("PLANNER step 4 | nodeId={} | plan input | length={} | snippet={}", node.getId(), planInputLen, planInputSnippet);

        String subtreeCreatorPluginRef = NodeParams.paramString(node, "subtreeCreatorPluginRef");
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
                List<ExecutionTreeNode> builtNodes = buildNodesFromCreatorSteps(steps);
                log.info("PLANNER step 6a | nodeId={} | builtNodes from creator | count={} | stepRefs={}", node.getId(), builtNodes.size(),
                        builtNodes.stream().map(ExecutionTreeNode::getPluginRef).filter(Objects::nonNull).toList());
                tree.attachChildren(node.getId(), builtNodes);
                log.info("PLANNER step 7a | nodeId={} | attachChildren done | stepsCount={} | stepRefs={}", node.getId(), builtNodes.size(),
                        builtNodes.stream().map(ExecutionTreeNode::getPluginRef).filter(Objects::nonNull).toList());
            }
            log.info("PLANNER step 8a | nodeId={} | exit (subtreeCreator path)", node.getId());
            return null;
        }

        log.info("PLANNER step 5b | nodeId={} | using parser (no subtreeCreator)", node.getId());
        String parserName = NodeParams.paramString(node, "parser");
        if (parserName == null || parserName.isBlank()) parserName = NodeParams.paramString(node, "treeBuilder");
        if (parserName == null || parserName.isBlank()) parserName = "default";
        SubtreeBuilder builder = SubtreeBuilderRegistry.get(parserName);
        if (builder == null) {
            log.warn("PLANNER node {}: no parser for '{}'", node.getId(), parserName);
            return null;
        }
        log.info("PLANNER step 6b | nodeId={} | parser={} | calling builder.build", node.getId(), parserName);
        SubtreeBuilder.BuildResult buildResult = builder.build(planResultJson);
        log.info("PLANNER step 6c | nodeId={} | parser build done | nodesCount={}", node.getId(), buildResult.nodes().size());
        for (Map.Entry<String, Object> e : buildResult.variablesToInject().entrySet()) {
            variableEngine.put(e.getKey(), e.getValue());
        }
        List<ExecutionTreeNode> builtNodes = buildResult.nodes();
        if (!builtNodes.isEmpty()) {
            log.info("PLANNER step 7b | nodeId={} | attachChildren | parentId={} | count={} | stepRefs={}", node.getId(), node.getId(), builtNodes.size(),
                    builtNodes.stream().map(ExecutionTreeNode::getPluginRef).filter(Objects::nonNull).toList());
            tree.attachChildren(node.getId(), builtNodes);
            log.info("PLANNER step 8b | nodeId={} | attachChildren done | parser={} | stepsCount={} | stepRefs={}", node.getId(), parserName, builtNodes.size(),
                    builtNodes.stream().map(ExecutionTreeNode::getPluginRef).filter(Objects::nonNull).toList());
        } else {
            log.info("PLANNER step 8b | nodeId={} | no nodes to attach | parser={}", node.getId(), parserName);
        }
        log.info("PLANNER step 9 | nodeId={} | exit", node.getId());
        return null;
    }

    /**
     * Runs planner logic only (model + parse + inject variables). Returns the list of step nodes
     * without attaching to any tree. Used when each step should run as a separate Temporal activity.
     */
    public List<ExecutionTreeNode> runPlannerReturnSteps(ExecutionTreeNode node, PipelineDefinition pipeline,
                                                         VariableEngine variableEngine, String queueName) {
        String planInputVariable = NodeParams.paramString(node, "planInputVariable");
        if (planInputVariable == null || planInputVariable.isBlank()) planInputVariable = "__planner_result";
        String modelPluginRef = NodeParams.paramString(node, "modelPluginRef");
        boolean interpretOnly = (modelPluginRef == null || modelPluginRef.isBlank());

        String planResultJson;
        if (!interpretOnly) {
            String resultVariable = NodeParams.paramString(node, "resultVariable");
            if (resultVariable == null || resultVariable.isBlank()) resultVariable = "__planner_result";
            String userQueryVariable = NodeParams.paramString(node, "userQueryVariable");
            if (userQueryVariable == null || userQueryVariable.isBlank()) userQueryVariable = "userQuery";
            String promptVar = "__planner_prompt";
            String template = PromptTemplateProvider.getTemplate(queueName);
            if (template == null || template.isBlank()) template = PromptTemplateProvider.getTemplate("default");
            if (template == null || template.isBlank()) {
                log.warn("PLANNER node {}: no template for queue {} or default", node.getId(), queueName);
                return List.of();
            }
            Object userQueryObj = variableEngine.get(userQueryVariable);
            String userQuery = userQueryObj != null ? userQueryObj.toString() : "";
            String filledPrompt = template.replace(PlannerContract.USER_QUERY_PLACEHOLDER, userQuery);
            variableEngine.put(promptVar, filledPrompt);
            Map<String, String> inputVarToParam = new LinkedHashMap<>();
            inputVarToParam.put(promptVar, "prompt");
            Map<String, String> outputParamToVar = new LinkedHashMap<>();
            outputParamToVar.put("responseText", resultVariable);
            pluginInvoker.invokeWithVariableMapping(modelPluginRef, inputVarToParam, outputParamToVar, variableEngine);
            Object planResultObj = variableEngine.get(resultVariable);
            planResultJson = planResultObj != null ? planResultObj.toString() : "";
        } else {
            Object planResultObj = variableEngine.get(planInputVariable);
            planResultJson = planResultObj != null ? planResultObj.toString() : "";
        }

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
                return buildNodesFromCreatorSteps(steps);
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
        SubtreeBuilder.BuildResult buildResult = builder.build(planResultJson);
        for (Map.Entry<String, Object> e : buildResult.variablesToInject().entrySet()) {
            variableEngine.put(e.getKey(), e.getValue());
        }
        return buildResult.nodes();
    }

    private Object executeSequence(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        for (ExecutionTreeNode child : node.getChildren()) {
            runChild.run(child, pipeline, variableEngine, queueName);
        }
        return null;
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

    private Object executeCase(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        for (ExecutionTreeNode child : node.getChildren()) {
            runChild.run(child, pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeIterator(ExecutionTreeNode node, PipelineDefinition pipeline,
                                   VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String collectionVar = NodeParams.paramString(node, "collectionVariable");
        String itemVar = NodeParams.paramString(node, "itemVariable");
        String indexVar = NodeParams.paramString(node, "indexVariable");
        if (collectionVar == null || itemVar == null) {
            log.warn("ITERATOR node {} missing collectionVariable or itemVariable in params", node.getId());
            return null;
        }
        Object coll = variableEngine.get(collectionVar);
        if (!(coll instanceof Collection)) {
            log.warn("ITERATOR node {} collectionVariable {} is not a Collection", node.getId(), collectionVar);
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        ExecutionTreeNode body = children.get(0);
        int index = 0;
        for (Object item : (Collection<?>) coll) {
            variableEngine.put(itemVar, item);
            if (indexVar != null && !indexVar.isBlank()) {
                variableEngine.put(indexVar, index);
            }
            runChild.run(body, pipeline, variableEngine, queueName);
            index++;
        }
        return null;
    }

    private Object executeFork(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName,
                               ChildNodeRunner runChild, ChildNodeRunner runChildSync) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        boolean runParallel = executionType == ExecutionType.ASYNC && executor != null && children.size() > 1;
        if (runParallel) {
            List<Future<?>> futures = new ArrayList<>(children.size());
            for (ExecutionTreeNode child : children) {
                Future<?> future = executor.submit(() -> {
                    if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                        LedgerContext.setRunId(ledgerRunId);
                    }
                    try {
                        runChildSync.run(child, pipeline, variableEngine, queueName);
                    } finally {
                        if (ledgerRunId != null && !ledgerRunId.isBlank()) {
                            LedgerContext.clear();
                        }
                    }
                });
                futures.add(future);
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                }
            }
        } else {
            for (ExecutionTreeNode child : children) {
                runChild.run(child, pipeline, variableEngine, queueName);
            }
        }
        return null;
    }

    private Object executeJoin(ExecutionTreeNode node, PipelineDefinition pipeline,
                               VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String strategy = NodeParams.paramString(node, "mergeStrategy");
        if (strategy == null || strategy.isBlank()) {
            log.warn("JOIN node {} missing mergeStrategy in params; required (ALL, ANY, FIRST_WINS, LAST_WINS, MAJORITY, REDUCE, PLUGIN). REDUCE/PLUGIN use pluginRef (e.g. OUTPUT_REDUCER) and input/outputMappings.", node.getId());
            return null;
        }
        List<ExecutionTreeNode> children = node.getChildren();
        switch (strategy.toUpperCase()) {
            case "ANY":
            case "FIRST_WINS":
                if (!children.isEmpty()) {
                    runChild.run(children.get(0), pipeline, variableEngine, queueName);
                }
                break;
            case "LAST_WINS":
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                break;
            case "PLUGIN":
            case "REDUCE":
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                if (node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
                    return pluginInvoker.invoke(node, variableEngine);
                }
                break;
            case "ALL":
            case "MAJORITY":
            default:
                for (ExecutionTreeNode child : children) {
                    runChild.run(child, pipeline, variableEngine, queueName);
                }
                break;
        }
        return null;
    }

    private Object executeTryCatch(ExecutionTreeNode node, PipelineDefinition pipeline,
                                  VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) return null;
        try {
            runChild.run(children.get(0), pipeline, variableEngine, queueName);
        } catch (Throwable t) {
            log.warn("TRY_CATCH node {} try-block failed: {} (running catch block or rethrowing)", node.getId(), t.getMessage(), t);
            String errorVar = NodeParams.paramString(node, "errorVariable");
            if (errorVar != null && !errorVar.isBlank()) {
                variableEngine.put(errorVar, t.getMessage() != null ? t.getMessage() : t.toString());
            }
            if (children.size() > 1) {
                runChild.run(children.get(1), pipeline, variableEngine, queueName);
            } else {
                throw t;
            }
        }
        return null;
    }

    private Object executeRetry(ExecutionTreeNode node, PipelineDefinition pipeline,
                                VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        List<ExecutionTreeNode> children = node.getChildren();
        if (children.isEmpty()) {
            log.warn("RETRY node {} has no child", node.getId());
            return null;
        }
        int maxAttempts = NodeParams.paramInt(node, "maxAttempts", 3);
        long initialMs = NodeParams.paramLong(node, "initialIntervalMs", 0L);
        double backoffCoefficient = NodeParams.paramDouble(node, "backoffCoefficient", 2.0);
        Throwable last = null;
        ExecutionTreeNode child = children.get(0);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                runChild.run(child, pipeline, variableEngine, queueName);
                if (attempt > 1) {
                    log.info("RETRY node {} child {} succeeded on attempt {}/{}", node.getId(), child.getId(), attempt, maxAttempts);
                }
                return null;
            } catch (Throwable t) {
                last = t;
                log.warn("RETRY node {} child {} attempt {}/{} failed: {}",
                        node.getId(), child.getId(), attempt, maxAttempts, t.getMessage(), t);
                if (attempt == maxAttempts) {
                    log.error("RETRY node {} child {} all {} attempts exhausted; failing", node.getId(), child.getId(), maxAttempts);
                    break;
                }
                if (!NodeParams.isRetryable(node, t)) {
                    log.warn("RETRY node {} child {} error not retryable; failing without further attempts", node.getId(), child.getId());
                    throw t;
                }
                long sleepMs = (long) (initialMs * Math.pow(backoffCoefficient, attempt - 1));
                if (sleepMs > 0) {
                    log.info("RETRY node {} child {} backing off {} ms before attempt {}/{}", node.getId(), child.getId(), sleepMs, attempt + 1, maxAttempts);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", e);
                    }
                }
            }
        }
        throw last instanceof RuntimeException ? (RuntimeException) last : new RuntimeException(last);
    }

    private Object executeSubPipeline(ExecutionTreeNode node, PipelineDefinition pipeline,
                                     VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        if (config == null || config.getPipelines() == null) {
            log.warn("SUB_PIPELINE node {} has no PipelineConfiguration; skipping", node.getId());
            return null;
        }
        String pipelineRef = NodeParams.paramString(node, "pipelineRef");
        if (pipelineRef == null || pipelineRef.isBlank()) {
            log.warn("SUB_PIPELINE node {} missing pipelineRef in params", node.getId());
            return null;
        }
        PipelineDefinition subPipeline = config.getPipelines().get(pipelineRef);
        if (subPipeline == null) {
            log.warn("SUB_PIPELINE node {} pipelineRef '{}' not found in config", node.getId(), pipelineRef);
            return null;
        }
        ExecutionTreeNode subRoot = subPipeline.getExecutionTree();
        if (subRoot != null) {
            runChild.run(subRoot, subPipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeEventWait(ExecutionTreeNode node, VariableEngine variableEngine) {
        String resultVar = NodeParams.paramString(node, "resultVariable");
        if (resultVar != null && !resultVar.isBlank()) {
            Object existing = variableEngine.get(resultVar);
            if (existing != null) {
                return existing;
            }
        }
        log.debug("EVENT_WAIT node {}: no blocking in activity; resultVariable left unset", node.getId());
        return null;
    }

    private Object executeLlmDecision(ExecutionTreeNode node, VariableEngine variableEngine) {
        String pluginRef = NodeParams.paramString(node, "pluginRef");
        String promptVar = NodeParams.paramString(node, "promptVariable");
        String outputVar = NodeParams.paramString(node, "outputVariable");
        if (pluginRef == null || promptVar == null || outputVar == null) {
            log.warn("LLM_DECISION node {} missing pluginRef, promptVariable or outputVariable", node.getId());
            return null;
        }
        Map<String, String> inputVarToParam = new LinkedHashMap<>();
        inputVarToParam.put(promptVar, "prompt");
        Map<String, String> outputParamToVar = new LinkedHashMap<>();
        outputParamToVar.put("responseText", outputVar);
        return pluginInvoker.invokeWithVariableMapping(pluginRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    private Object executeToolRouter(ExecutionTreeNode node, PipelineDefinition pipeline,
                                    VariableEngine variableEngine, String queueName, ChildNodeRunner runChild) {
        String inputVar = NodeParams.paramString(node, "inputVariable");
        if (inputVar == null || inputVar.isBlank()) {
            log.warn("TOOL_ROUTER node {} missing inputVariable in params", node.getId());
            return null;
        }
        Object value = variableEngine.get(inputVar);
        for (ExecutionTreeNode child : node.getChildren()) {
            Object caseVal = child.getParams() != null ? child.getParams().get("caseValue") : null;
            if (caseVal == null && child.getParams() != null) {
                caseVal = child.getParams().get("toolValue");
            }
            if (Objects.equals(value, caseVal) || (value != null && value.toString().equals(caseVal != null ? caseVal.toString() : null))) {
                runChild.run(child, pipeline, variableEngine, queueName);
                return null;
            }
        }
        if (!node.getChildren().isEmpty()) {
            runChild.run(node.getChildren().get(0), pipeline, variableEngine, queueName);
        }
        return null;
    }

    private Object executeEvaluation(ExecutionTreeNode node, VariableEngine variableEngine) {
        String evaluatorRef = NodeParams.paramString(node, "evaluatorRef");
        String inputVar = NodeParams.paramString(node, "inputVariable");
        String outputVar = NodeParams.paramString(node, "outputVariable");
        if (evaluatorRef == null || inputVar == null || outputVar == null) {
            log.warn("EVALUATION node {} missing evaluatorRef, inputVariable or outputVariable", node.getId());
            return null;
        }
        Map<String, String> inputVarToParam = new LinkedHashMap<>();
        inputVarToParam.put(inputVar, "input");
        Map<String, String> outputParamToVar = new LinkedHashMap<>();
        outputParamToVar.put("result", outputVar);
        outputParamToVar.put("score", outputVar);
        return pluginInvoker.invokeWithVariableMapping(evaluatorRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    private Object executeReflection(ExecutionTreeNode node, VariableEngine variableEngine) {
        String pluginRef = NodeParams.paramString(node, "pluginRef");
        String inputVar = NodeParams.paramString(node, "inputVariable");
        String outputVar = NodeParams.paramString(node, "outputVariable");
        if (pluginRef == null || inputVar == null || outputVar == null) {
            log.warn("REFLECTION node {} missing pluginRef, inputVariable or outputVariable", node.getId());
            return null;
        }
        Map<String, String> inputVarToParam = new LinkedHashMap<>();
        inputVarToParam.put(inputVar, "prompt");
        Map<String, String> outputParamToVar = new LinkedHashMap<>();
        outputParamToVar.put("responseText", outputVar);
        return pluginInvoker.invokeWithVariableMapping(pluginRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    /**
     * Builds execution tree nodes from SUBTREE_CREATOR plugin output. Each step is a map with "pluginRef" and "prompt".
     */
    private static List<ExecutionTreeNode> buildNodesFromCreatorSteps(List<Map<String, Object>> steps) {
        List<ExecutionTreeNode> nodes = new ArrayList<>();
        List<String> emptyStrList = List.of();
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
            nodes.add(child);
        }
        return nodes;
    }

    /**
     * Fills a prompt template and writes to an output variable. Use in OLO-aligned planner flows:
     * FILL_TEMPLATE → PLUGIN (model) → PLANNER (interpret-only).
     * Params: templateKey (optional, for template provider; e.g. queue or "default"), template (optional, inline),
     * userQueryVariable (default "userQuery"), outputVariable (default "__planner_prompt").
     */
    private Object executeFillTemplate(ExecutionTreeNode node, VariableEngine variableEngine, String queueName) {
        String templateKey = NodeParams.paramString(node, "templateKey");
        String inlineTemplate = NodeParams.paramString(node, "template");
        String template = inlineTemplate != null && !inlineTemplate.isBlank()
                ? inlineTemplate
                : (templateKey != null && !templateKey.isBlank() ? PromptTemplateProvider.getTemplate(templateKey) : PromptTemplateProvider.getTemplate(queueName));
        if (template == null || template.isBlank()) {
            template = PromptTemplateProvider.getTemplate("default");
        }
        if (template == null || template.isBlank()) {
            log.warn("FILL_TEMPLATE node {}: no template from provider (templateKey={}, queue={})", node.getId(), templateKey, queueName);
            return null;
        }
        String userQueryVariable = NodeParams.paramString(node, "userQueryVariable");
        if (userQueryVariable == null || userQueryVariable.isBlank()) userQueryVariable = "userQuery";
        String outputVariable = NodeParams.paramString(node, "outputVariable");
        if (outputVariable == null || outputVariable.isBlank()) outputVariable = "__planner_prompt";
        Object userQueryObj = variableEngine.get(userQueryVariable);
        String userQuery = userQueryObj != null ? userQueryObj.toString() : "";
        String filled = template.replace(PlannerContract.USER_QUERY_PLACEHOLDER, userQuery);
        variableEngine.put(outputVariable, filled);
        if (log.isDebugEnabled()) {
            log.debug("FILL_TEMPLATE node {}: wrote {} chars to {}", node.getId(), filled.length(), outputVariable);
        }
        return null;
    }
}
