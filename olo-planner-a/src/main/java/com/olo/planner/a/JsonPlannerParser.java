package com.olo.planner.a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Single responsibility: parse planner output text into a JsonNode array and normalize step elements.
 * Encapsulates all LLM-output quirks (wrapped strings, over-escaping, double brackets, etc.).
 */
final class JsonPlannerParser {

    private static final Logger log = LoggerFactory.getLogger(JsonPlannerParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    JsonNode parse(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;
        String trimmed = rawText.trim();
        String toParse = unwrapArrayOfJsonString(trimmed);
        return parseRoot(toParse, trimmed);
    }

    /**
     * Normalizes array elements so we support both direct step objects and double-encoded strings.
     * Some models return {@code [{"toolId":"...","input":{...}}]} while others return
     * {@code ["{\"toolId\":\"...\",\"input\":{...}}"]} (array of JSON strings). This unwraps
     * string elements by parsing them as JSON and flattens to a list of step objects.
     */
    List<JsonNode> normalizeStepElements(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
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
            }
        }
        return out;
    }

    /** Parses planner output with fallbacks for common LLM output quirks. Returns null on parse failure. */
    private JsonNode parseRoot(String toParse, String trimmed) {
        JsonNode root = null;
        try {
            return MAPPER.readTree(toParse);
        } catch (JsonProcessingException e) {
            String fallback = unwrapAndUnescape(trimmed);
            if (!fallback.equals(toParse)) {
                try {
                    return MAPPER.readTree(fallback);
                } catch (JsonProcessingException e2) {
                    root = tryExtractInnerJsonFromWrappedString(trimmed);
                    if (root == null) root = tryUnwrapDoubleBracketAndUnescapeNewlines(trimmed);
                }
            } else {
                root = tryExtractInnerJsonFromWrappedString(trimmed);
                if (root == null) root = tryUnwrapDoubleBracketAndUnescapeNewlines(trimmed);
            }
            if (root == null && log.isWarnEnabled()) {
                int maxLog = 600;
                String snippet = trimmed.length() > maxLog ? trimmed.substring(0, maxLog) + "...[truncated length=" + trimmed.length() + "]" : trimmed;
                log.warn("Planner output JSON parse failed: {} | raw snippet=[{}]", e.getMessage(), snippet);
            }
        }
        if (root == null && trimmed != null && trimmed.startsWith("[[") && !trimmed.startsWith("[\"")) {
            root = tryUnwrapDoubleBracketAndUnescapeNewlines(trimmed);
        }
        return root;
    }

    /**
     * If the model returns an array whose single element is a JSON string (e.g. {@code ["{\"toolId\":\"...\"}"]}
     * or the unescaped variant {@code ["{"toolId":"..."}"]}), unwraps to a valid array of one object
     * {@code [{"toolId":"..."}]} so parsing succeeds.
     */
    private static String unwrapArrayOfJsonString(String trimmed) {
        if (trimmed == null || trimmed.length() < 5) return trimmed;
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
     */
    private static String unwrapAndUnescape(String trimmed) {
        if (trimmed == null || trimmed.length() < 5) return trimmed;
        if (!trimmed.startsWith("[\"") || !trimmed.endsWith("\"]")) return trimmed;
        String inner = trimmed.substring(2, trimmed.length() - 2).trim();
        if (inner.isEmpty() || (!inner.startsWith("{") && !inner.startsWith("["))) return trimmed;
        String unescaped = inner.replace("\\\\", "\\").replace("\\\"", "\"");
        return "[" + unescaped + "]";
    }

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
}

