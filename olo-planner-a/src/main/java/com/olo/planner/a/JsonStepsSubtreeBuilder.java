package com.olo.planner.a;

import com.fasterxml.jackson.databind.JsonNode;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.DynamicNodeExpansionRequest;
import com.olo.node.NodeSpec;
import com.olo.node.PipelineFeatureContext;
import com.olo.planner.SubtreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default JSON-array planner parser. Expects a JSON array of steps, e.g.:
 * [ {"toolId": "research", "input": {"prompt": "..."}}, ... ]
 * Supports planner-spawns-planner: steps with {@code "type": "PLANNER"} and optional
 * {@code "params": {"modelPluginRef": "...", "treeBuilder": "default", ...}} produce
 * PLANNER child nodes that expand when executed.
 * Produces one NodeSpec per step (PLUGIN or PLANNER). Registered under "default" and
 * {@link com.olo.planner.SubtreeBuilderRegistry#DEFAULT_JSON_ARRAY_PARSER}.
 */
public final class JsonStepsSubtreeBuilder implements SubtreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(JsonStepsSubtreeBuilder.class);
    private final JsonPlannerParser parser = new JsonPlannerParser();
    private final JsonPlannerStepExtractor extractor = new JsonPlannerStepExtractor();
    private final JsonPlannerNodeFactory nodeFactory = new JsonPlannerNodeFactory();

    @Override
    public ExpansionBuildResult buildExpansion(String plannerOutputText, String plannerNodeId) {
        List<NodeSpec> specs = new ArrayList<>();
        Map<String, Object> variablesToInject = new LinkedHashMap<>();
        if (plannerOutputText == null || plannerOutputText.isBlank()) {
            log.warn("Planner output is null or blank; returning empty expansion");
            return new ExpansionBuildResult(new DynamicNodeExpansionRequest(plannerNodeId != null ? plannerNodeId : "", List.of()), variablesToInject);
        }
        String trimmed = plannerOutputText.trim();
        JsonNode root = parser.parse(trimmed);
        if (root == null) {
            log.warn("Planner output could not be parsed as JSON; returning empty expansion. Expected a JSON array of steps, e.g. [{\"toolId\":\"...\", \"input\":{\"prompt\":\"...\"}}]. Snippet (first 400 chars): {}", trimmed.length() > 400 ? trimmed.substring(0, 400) + "..." : trimmed);
        } else if (!root.isArray()) {
            log.warn("Planner output is not a JSON array; returning empty expansion. Expected format: [{\"toolId\":\"...\", \"input\":{\"prompt\":\"...\"}}, ...]. Root type={}", root.getNodeType());
        }
        if (root != null && root.isArray()) {
            List<JsonNode> stepObjects = parser.normalizeStepElements(root);
            if (stepObjects.isEmpty()) {
                log.warn("Planner output is a JSON array but has no valid step objects (each step must have \"toolId\"); returning empty expansion. Snippet: {}", trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
            }
            for (int i = 0; i < stepObjects.size(); i++) {
                JsonPlannerStepInfo info = extractor.extract(stepObjects.get(i), i);
                if (info == null) continue;
                specs.add(nodeFactory.toNodeSpec(info, i, variablesToInject));
            }
        }
        return new ExpansionBuildResult(
                new DynamicNodeExpansionRequest(plannerNodeId != null ? plannerNodeId : "", specs),
                variablesToInject);
    }

    @Override
    public BuildResult build(String plannerOutputJson) {
        List<ExecutionTreeNode> nodes = new ArrayList<>();
        Map<String, Object> variablesToInject = new LinkedHashMap<>();
        if (plannerOutputJson == null || plannerOutputJson.isBlank()) {
            log.warn("Planner output is null or blank; returning empty subtree");
            return new BuildResult(nodes, variablesToInject);
        }
        String trimmed = plannerOutputJson.trim();
        JsonNode root = parser.parse(trimmed);
        if (root != null && root.isArray()) {
            List<JsonNode> stepObjects = parser.normalizeStepElements(root);
            for (int i = 0; i < stepObjects.size(); i++) {
                JsonPlannerStepInfo info = extractor.extract(stepObjects.get(i), i);
                if (info == null) continue;
                nodes.add(nodeFactory.toExecutionNode(info, i, variablesToInject));
            }
        }
        return new BuildResult(nodes, variablesToInject);
    }

    @Override
    public BuildResult build(String plannerOutputText, DynamicNodeBuilder nodeBuilder, PipelineFeatureContext context) {
        if (nodeBuilder == null || context == null) {
            return build(plannerOutputText);
        }
        List<ExecutionTreeNode> nodes = new ArrayList<>();
        Map<String, Object> variablesToInject = new LinkedHashMap<>();
        if (plannerOutputText == null || plannerOutputText.isBlank()) {
            log.warn("Planner output is null or blank; returning empty subtree");
            return new BuildResult(nodes, variablesToInject);
        }
        String trimmed = plannerOutputText.trim();
        JsonNode root = parser.parse(trimmed);
        if (root != null && root.isArray()) {
            List<JsonNode> stepObjects = parser.normalizeStepElements(root);
            for (int i = 0; i < stepObjects.size(); i++) {
                JsonPlannerStepInfo info = extractor.extract(stepObjects.get(i), i);
                if (info == null) continue;
                nodes.add(nodeBuilder.buildNode(
                        nodeFactory.toDynamicSpec(info, i, variablesToInject),
                        context
                ));
            }
        }
        return new BuildResult(nodes, variablesToInject);
    }
}
