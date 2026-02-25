package com.olo.planner.a;

import com.olo.config.TenantConfig;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.plugin.ExecutablePlugin;
import com.olo.planner.SubtreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SUBTREE_CREATOR plugin: takes plan text (e.g. from a planner model), parses it with
 * {@link JsonStepsSubtreeBuilder}, and returns the contract output (variablesToInject + steps)
 * so the engine can build and run the subtree.
 * <p>
 * Input: "planText" (string). Output: "variablesToInject" (Map), "steps" (List of Map with "pluginRef", "prompt").
 */
public final class JsonStepsSubtreeCreatorPlugin implements ExecutablePlugin {

    private static final Logger log = LoggerFactory.getLogger(JsonStepsSubtreeCreatorPlugin.class);
    private static final SubtreeBuilder BUILDER = new JsonStepsSubtreeBuilder();

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        Object planObj = inputs != null ? inputs.get("planText") : null;
        String planText = planObj != null ? planObj.toString() : "";
        SubtreeBuilder.BuildResult result = BUILDER.build(planText);

        Map<String, Object> variablesToInject = new LinkedHashMap<>(result.variablesToInject());
        List<Map<String, Object>> steps = new ArrayList<>();
        List<ExecutionTreeNode> nodes = result.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            ExecutionTreeNode node = nodes.get(i);
            String pluginRef = node.getPluginRef();
            if (pluginRef == null || pluginRef.isBlank()) continue;
            String promptVar = "__planner_step_" + i + "_prompt";
            Object prompt = result.variablesToInject().get(promptVar);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("pluginRef", pluginRef);
            step.put("prompt", prompt != null ? prompt : "");
            steps.add(step);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("variablesToInject", variablesToInject);
        output.put("steps", steps);
        if (log.isDebugEnabled()) {
            log.debug("Subtree creator: steps={}, variablesToInject keys={}", steps.size(), variablesToInject.size());
        }
        return output;
    }
}
