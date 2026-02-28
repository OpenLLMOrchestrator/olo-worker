package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.worker.engine.VariableEngine;
import com.olo.worker.engine.node.NodeParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single responsibility: execute EVENT_WAIT, LLM_DECISION, TOOL_ROUTER, EVALUATION, REFLECTION, FILL_TEMPLATE nodes.
 */
final class ToolingExecutions {

    private static final Logger log = LoggerFactory.getLogger(ToolingExecutions.class);

    static Object executeEventWait(ExecutionTreeNode node, VariableEngine variableEngine) {
        String resultVar = NodeParams.paramString(node, "resultVariable");
        if (resultVar != null && !resultVar.isBlank()) {
            Object existing = variableEngine.get(resultVar);
            if (existing != null) return existing;
        }
        log.debug("EVENT_WAIT node {}: no blocking in activity; resultVariable left unset", node.getId());
        return null;
    }

    static Object executeLlmDecision(ExecutionTreeNode node, VariableEngine variableEngine, HandlerContext ctx) {
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
        return ctx.getPluginInvoker().invokeWithVariableMapping(pluginRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    static Object executeToolRouter(ExecutionTreeNode node, PipelineDefinition pipeline,
                                    VariableEngine variableEngine, String queueName, com.olo.worker.engine.node.ChildNodeRunner runChild) {
        String inputVar = NodeParams.paramString(node, "inputVariable");
        if (inputVar == null || inputVar.isBlank()) {
            log.warn("TOOL_ROUTER node {} missing inputVariable in params", node.getId());
            return null;
        }
        Object value = variableEngine.get(inputVar);
        for (ExecutionTreeNode child : node.getChildren()) {
            Object caseVal = child.getParams() != null ? child.getParams().get("caseValue") : null;
            if (caseVal == null && child.getParams() != null) caseVal = child.getParams().get("toolValue");
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

    static Object executeEvaluation(ExecutionTreeNode node, VariableEngine variableEngine, HandlerContext ctx) {
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
        return ctx.getPluginInvoker().invokeWithVariableMapping(evaluatorRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    static Object executeReflection(ExecutionTreeNode node, VariableEngine variableEngine, HandlerContext ctx) {
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
        return ctx.getPluginInvoker().invokeWithVariableMapping(pluginRef, inputVarToParam, outputParamToVar, variableEngine);
    }

    static Object executeFillTemplate(ExecutionTreeNode node, VariableEngine variableEngine, String queueName) {
        String templateKey = NodeParams.paramString(node, "templateKey");
        String inlineTemplate = NodeParams.paramString(node, "template");
        String template = inlineTemplate != null && !inlineTemplate.isBlank()
                ? inlineTemplate
                : (templateKey != null && !templateKey.isBlank()
                        ? com.olo.planner.PromptTemplateProvider.getTemplate(templateKey)
                        : com.olo.planner.PromptTemplateProvider.getTemplate(queueName));
        if (template == null || template.isBlank()) template = com.olo.planner.PromptTemplateProvider.getTemplate("default");
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
        String filled = template.replace(com.olo.planner.PlannerContract.USER_QUERY_PLACEHOLDER, userQuery);
        variableEngine.put(outputVariable, filled);
        if (log.isDebugEnabled()) log.debug("FILL_TEMPLATE node {}: wrote {} chars to {}", node.getId(), filled.length(), outputVariable);
        return null;
    }
}
