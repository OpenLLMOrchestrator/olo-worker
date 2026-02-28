package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.planner.PlannerContract;
import com.olo.planner.PromptTemplateProvider;
import com.olo.worker.engine.PluginInvoker;
import com.olo.worker.engine.VariableEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single responsibility: resolve planner result JSON (model call or interpret-only from variable map).
 */
final class PlannerPlanResolver {

    private static final Logger log = LoggerFactory.getLogger(PlannerPlanResolver.class);

    private PlannerPlanResolver() {
    }

    static String resolvePlanResultJson(ExecutionTreeNode node,
                                        VariableEngine variableEngine,
                                        String queueName,
                                        boolean interpretOnly,
                                        String planInputVariable,
                                        String modelPluginRef,
                                        PluginInvoker pluginInvoker) {
        if (!interpretOnly) {
            log.info("PLANNER step 3a | nodeId={} | resolving template and userQuery", node.getId());
            String resultVariable = com.olo.worker.engine.node.NodeParams.paramString(node, "resultVariable");
            if (resultVariable == null || resultVariable.isBlank()) resultVariable = "__planner_result";
            String userQueryVariable = com.olo.worker.engine.node.NodeParams.paramString(node, "userQueryVariable");
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
                String promptSnippet = filledPrompt.length() > maxLog
                        ? filledPrompt.substring(0, maxLog) + "...[truncated]"
                        : filledPrompt;
                log.info("PLANNER step 3b | nodeId={} | invoking model | pluginRef={} | userQuery length={} | filledPrompt length={} | snippet={}",
                        node.getId(), modelPluginRef, userQuery.length(), filledPrompt.length(), promptSnippet);
            }
            Map<String, String> inputVarToParam = new LinkedHashMap<>();
            inputVarToParam.put(promptVar, "prompt");
            Map<String, String> outputParamToVar = new LinkedHashMap<>();
            outputParamToVar.put("responseText", resultVariable);
            pluginInvoker.invokeWithVariableMapping(modelPluginRef, inputVarToParam, outputParamToVar, variableEngine);
            Object planResultObj = variableEngine.get(resultVariable);
            String planResultJson = planResultObj != null ? planResultObj.toString() : "";
            log.info("PLANNER step 3c | nodeId={} | model returned | result length={}",
                    node.getId(), planResultJson != null ? planResultJson.length() : 0);
            return planResultJson;
        }
        log.info("PLANNER step 3d | nodeId={} | reading plan from variable {}", node.getId(), planInputVariable);
        Object planResultObj = variableEngine.get(planInputVariable);
        return planResultObj != null ? planResultObj.toString() : "";
    }
}

