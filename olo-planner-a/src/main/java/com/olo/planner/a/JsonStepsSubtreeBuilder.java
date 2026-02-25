package com.olo.planner.a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.executiontree.tree.ParameterMapping;
import com.olo.planner.SubtreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default JSON-array planner parser. Expects a JSON array of steps, e.g.:
 * [ {"toolId": "research", "input": {"prompt": "..."}}, ... ]
 * Produces one PLUGIN node per step. Registered under "default" and {@link com.olo.planner.SubtreeBuilderRegistry#DEFAULT_JSON_ARRAY_PARSER}.
 */
public final class JsonStepsSubtreeBuilder implements SubtreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(JsonStepsSubtreeBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public BuildResult build(String plannerOutputJson) {
        List<ExecutionTreeNode> nodes = new ArrayList<>();
        Map<String, Object> variablesToInject = new LinkedHashMap<>();
        if (plannerOutputJson == null || plannerOutputJson.isBlank()) {
            log.warn("Planner output is null or blank; returning empty subtree");
            return new BuildResult(nodes, variablesToInject);
        }
        String trimmed = plannerOutputJson.trim();
        if (log.isDebugEnabled()) {
            int maxLog = 600;
            String snippet = trimmed.length() > maxLog ? trimmed.substring(0, maxLog) + "...[truncated]" : trimmed;
            log.debug("JsonStepsSubtreeBuilder input (length={}): {}", trimmed.length(), snippet);
        }
        String toParse = unwrapArrayOfJsonString(trimmed);
        JsonNode root = null;
        try {
            root = MAPPER.readTree(toParse);
        } catch (JsonProcessingException e) {
            String fallback = unwrapAndUnescape(trimmed);
            if (!fallback.equals(toParse)) {
                try {
                    root = MAPPER.readTree(fallback);
                    if (log.isDebugEnabled()) {
                        log.debug("Planner output parsed after unescape fallback");
                    }
                } catch (JsonProcessingException e2) {
                    root = tryExtractInnerJsonFromWrappedString(trimmed);
                    if (root == null) {
                        root = tryUnwrapDoubleBracketAndUnescapeNewlines(trimmed);
                    }
                    if (root == null) {
                        int maxLog = 600;
                        String snippet = trimmed.length() > maxLog ? trimmed.substring(0, maxLog) + "...[truncated length=" + trimmed.length() + "]" : trimmed;
                        log.warn("Planner output JSON parse failed: {} | raw snippet=[{}]", e.getMessage(), snippet);
                    } else if (log.isDebugEnabled()) {
                        log.debug("Planner output parsed after inner JSON extraction or [[\\n...\\n]] unwrap");
                    }
                }
            } else {
                root = tryExtractInnerJsonFromWrappedString(trimmed);
                if (root == null) {
                    root = tryUnwrapDoubleBracketAndUnescapeNewlines(trimmed);
                }
                if (root == null) {
                    int maxLog = 600;
                    String snippet = trimmed.length() > maxLog ? trimmed.substring(0, maxLog) + "...[truncated length=" + trimmed.length() + "]" : trimmed;
                    log.warn("Planner output JSON parse failed: {} | raw snippet=[{}]", e.getMessage(), snippet);
                } else if (log.isDebugEnabled()) {
                    log.debug("Planner output parsed after extracting inner JSON from [\"...\"] wrapper or [[\\n...\\n]]");
                }
            }
        }
        if (root == null && trimmed.startsWith("[[") && !trimmed.startsWith("[\"")) {
            root = tryUnwrapDoubleBracketAndUnescapeNewlines(trimmed);
        }
        if (root != null && root.isArray()) {
            List<JsonNode> stepObjects = normalizeStepElements(root);
            for (int i = 0; i < stepObjects.size(); i++) {
                JsonNode step = stepObjects.get(i);
                String toolId = step.has("toolId") ? step.get("toolId").asText(null) : null;
                if (toolId == null || toolId.isBlank()) continue;
                toolId = normalizePluginRef(toolId);
                JsonNode input = step.has("input") ? step.get("input") : null;
                String promptVar = "__planner_step_" + i + "_prompt";
                String responseVar = "__planner_step_" + i + "_response";
                String prompt = "";
                if (input != null && input.has("prompt")) {
                    prompt = input.get("prompt").asText("");
                }
                variablesToInject.put(promptVar, prompt);

                List<ParameterMapping> inputMappings = List.of(
                        new ParameterMapping("prompt", promptVar)
                );
                List<ParameterMapping> outputMappings = List.of(
                        new ParameterMapping("responseText", responseVar)
                );
                ExecutionTreeNode node = new ExecutionTreeNode(
                        UUID.randomUUID().toString(),
                        "step-" + i + "-" + toolId,
                        NodeType.PLUGIN,
                        List.of(),
                        "PLUGIN",
                        toolId,
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
                nodes.add(node);
            }
        }
        return new BuildResult(nodes, variablesToInject);
    }

    /**
     * If the model returns an array whose single element is a JSON string (e.g. {@code ["{\"toolId\":\"...\"}"]}
     * or the unescaped variant {@code ["{"toolId":"..."}"]}), unwraps to a valid array of one object
     * {@code [{"toolId":"..."}]} so parsing succeeds.
     */
    private static String unwrapArrayOfJsonString(String trimmed) {
        if (trimmed == null || trimmed.length() < 5) return trimmed;
        // Pattern: [" at start, "] at end (optional whitespace before ])
        if (trimmed.startsWith("[\"") && trimmed.endsWith("\"]")) {
            String inner = trimmed.substring(2, trimmed.length() - 2).trim();
            if (!inner.isEmpty() && (inner.startsWith("{") || inner.startsWith("["))) {
                return "[" + inner + "]";
            }
        }
        return trimmed;
    }

    /**
     * Unwraps {@code ["..."]} and unescapes the inner string so over-escaped model output
     * (e.g. {@code ["{\\"toolId\\": \\"RESEARCH_TOOL\\", ...}"]}) becomes valid JSON.
     * Replaces {@code \\\\} with {@code \} and {@code \\"} with {@code "} in the inner content.
     */
    private static String unwrapAndUnescape(String trimmed) {
        if (trimmed == null || trimmed.length() < 5) return trimmed;
        if (!trimmed.startsWith("[\"") || !trimmed.endsWith("\"]")) return trimmed;
        String inner = trimmed.substring(2, trimmed.length() - 2).trim();
        if (inner.isEmpty() || (!inner.startsWith("{") && !inner.startsWith("["))) return trimmed;
        String unescaped = inner.replace("\\\\", "\\").replace("\\\"", "\"");
        return "[" + unescaped + "]";
    }

    /**
     * When the model returns {@code [[\n  {"toolId":...}\n]]} (outer array with literal backslash-n),
     * strip the outer brackets and replace literal {@code \n} / {@code \t} with real newline/tab, then parse.
     */
    private static JsonNode tryUnwrapDoubleBracketAndUnescapeNewlines(String trimmed) {
        if (trimmed == null || trimmed.length() < 5 || !trimmed.startsWith("[[") || trimmed.startsWith("[\"")) return null;
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty() || (!inner.startsWith("[") && !inner.startsWith("{"))) return null;
        String normalized = inner.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
        try {
            JsonNode node = MAPPER.readTree(normalized);
            if (node.isArray()) return node;
            if (node.isObject() && node.has("toolId")) return MAPPER.createArrayNode().add(node);
            return null;
        } catch (JsonProcessingException e) {
            log.debug("Unwrap [[\\n...\\n]] parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * When the model returns {@code ["[{\"toolId\":...}]"]} with unescaped quotes inside the string
     * (so the outer JSON is invalid), extract the inner JSON by bracket matching and parse it.
     * Returns a JsonNode array, or null if extraction/parse fails.
     */
    private static JsonNode tryExtractInnerJsonFromWrappedString(String trimmed) {
        if (trimmed == null || trimmed.length() < 5 || !trimmed.startsWith("[\"")) return null;
        int start = 2; // after ["
        while (start < trimmed.length()) {
            char c = trimmed.charAt(start);
            if (Character.isWhitespace(c) || c == '"') {
                start++;
                continue;
            }
            break;
        }
        if (start >= trimmed.length()) return null;
        char first = trimmed.charAt(start);
        char open = first == '[' ? '[' : (first == '{' ? '{' : 0);
        char close = first == '[' ? ']' : (first == '{' ? '}' : 0);
        if (open == 0) return null;
        int depth = 0;
        int end = -1;
        boolean inString = false;
        char stringChar = 0;
        boolean escape = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (!inString) {
                if (c == '"' || c == '\'') {
                    inString = true;
                    stringChar = c;
                    continue;
                }
                if (c == open) {
                    depth++;
                    continue;
                }
                if (c == close) {
                    depth--;
                    if (depth == 0) {
                        end = i + 1;
                        break;
                    }
                    continue;
                }
                continue;
            }
            if (c == stringChar) {
                inString = false;
            }
        }
        if (end <= start) return null;
        String inner = trimmed.substring(start, end);
        try {
            JsonNode node = MAPPER.readTree(inner);
            if (node.isArray()) return node;
            if (node.isObject() && node.has("toolId")) return MAPPER.createArrayNode().add(node);
            return null;
        } catch (JsonProcessingException e) {
            log.debug("Extract inner JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Normalizes toolId from planner output so it matches registered plugin ids. LLMs often return
     * "ECHO TOOL" (space) while plugins are registered as "ECHO_TOOL" (underscore). Replaces spaces with underscores.
     */
    private static String normalizePluginRef(String toolId) {
        if (toolId == null || toolId.isBlank()) return toolId;
        return toolId.trim().replace(" ", "_");
    }

    /**
     * Normalizes array elements so we support both direct step objects and double-encoded strings.
     * Some models return {@code [{"toolId":"...","input":{...}}]} while others return
     * {@code ["{\"toolId\":\"...\",\"input\":{...}}"]} (array of JSON strings). This unwraps
     * string elements by parsing them as JSON and flattens to a list of step objects.
     */
    private static List<JsonNode> normalizeStepElements(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode el = root.get(i);
            if (el == null) continue;
            if (el.isObject() && el.has("toolId")) {
                out.add(el);
                continue;
            }
            if (el.isTextual()) {
                String raw = el.asText();
                if (raw == null || raw.isBlank()) continue;
                try {
                    JsonNode parsed = MAPPER.readTree(raw.trim());
                    if (parsed.isObject() && parsed.has("toolId")) {
                        out.add(parsed);
                    } else if (parsed.isArray()) {
                        for (int j = 0; j < parsed.size(); j++) {
                            JsonNode item = parsed.get(j);
                            if (item != null && item.isObject() && item.has("toolId")) {
                                out.add(item);
                            }
                        }
                    }
                } catch (JsonProcessingException e) {
                    log.debug("Planner step element [{}] string parse failed: {}", i, e.getMessage());
                }
                continue;
            }
        }
        return out;
    }
}
