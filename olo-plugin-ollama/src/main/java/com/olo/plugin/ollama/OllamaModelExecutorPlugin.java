package com.olo.plugin.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.annotations.OloPlugin;
import com.olo.annotations.OloPluginParam;
import com.olo.annotations.ResourceCleanup;
import com.olo.config.TenantConfig;
import com.olo.plugin.ModelExecutorPlugin;
import com.olo.plugin.PluginRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Model-executor plugin that calls the Ollama API to get AI model responses.
 * Registers with {@link PluginRegistry} under a configurable id (e.g. "GPT4_EXECUTOR" for olo-chat-queue-oolama).
 * <p>
 * Input: "prompt" (String). Output: "responseText" (String).
 * Uses {@code POST http://baseUrl/api/chat} with {@code model} and a single user message.
 */
@OloPlugin(
        id = "GPT4_EXECUTOR",
        displayName = "Ollama Model",
        contractType = "MODEL_EXECUTOR",
        description = "Calls Ollama API for chat completion (e.g. llama3.2)",
        category = "Model",
        inputParameters = { @OloPluginParam(name = "prompt", type = "STRING", required = true) },
        outputParameters = { @OloPluginParam(name = "responseText", type = "STRING", required = false) }
)
public final class OllamaModelExecutorPlugin implements ModelExecutorPlugin, ResourceCleanup {

    private static final String INPUT_PROMPT = "prompt";
    private static final String OUTPUT_RESPONSE_TEXT = "responseText";
    /** Output keys for metrics (used by MetricsFeature). */
    public static final String OUTPUT_PROMPT_TOKENS = "promptTokens";
    public static final String OUTPUT_COMPLETION_TOKENS = "completionTokens";
    public static final String OUTPUT_MODEL = "modelId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    /**
     * Creates a plugin using the given base URL (e.g. "http://localhost:11434") and model name (e.g. "llama3.2").
     */
    public OllamaModelExecutorPlugin(String baseUrl, String model) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:11434";
        this.model = model != null && !model.isBlank() ? model.trim() : "llama3.2";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Creates a plugin with default base URL http://localhost:11434 and model "llama3.2".
     */
    public OllamaModelExecutorPlugin() {
        this("http://localhost:11434", "llama3.2");
    }

    @Override
    public void onExit() {
        // HttpClient is not AutoCloseable in Java 17; on Java 21+ you may close it here to release resources
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        Object promptObj = inputs == null ? null : inputs.get(INPUT_PROMPT);
        String prompt = promptObj != null ? Objects.toString(promptObj).trim() : "";
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("ollamaBaseUrl") != null
                ? Objects.toString(tenantConfig.get("ollamaBaseUrl")).trim() : baseUrl;
        String effectiveModel = tenantConfig != null && tenantConfig.get("ollamaModel") != null
                ? Objects.toString(tenantConfig.get("ollamaModel")).trim() : model;
        ChatResult result = callOllamaChat(prompt, effectiveBaseUrl, effectiveModel);
        Map<String, Object> out = new HashMap<>();
        out.put(OUTPUT_RESPONSE_TEXT, result.content != null ? result.content : "");
        out.put(OUTPUT_PROMPT_TOKENS, result.promptTokens);
        out.put(OUTPUT_COMPLETION_TOKENS, result.completionTokens);
        out.put(OUTPUT_MODEL, result.model != null ? result.model : effectiveModel);
        return out;
    }

    private static final class ChatResult {
        final String content;
        final long promptTokens;
        final long completionTokens;
        final String model;

        ChatResult(String content, long promptTokens, long completionTokens, String model) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.model = model;
        }
    }

    private ChatResult callOllamaChat(String prompt, String effectiveBaseUrl, String effectiveModel) throws Exception {
        OllamaChatRequest.Message msg = new OllamaChatRequest.Message("user", prompt);
        OllamaChatRequest req = new OllamaChatRequest(effectiveModel, List.of(msg), false);
        String json = MAPPER.writeValueAsString(req);
        URI uri = URI.create(effectiveBaseUrl + "/api/chat");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode() + " " + response.body());
        }
        String body = response.body();
        OllamaChatResponse resp = MAPPER.readValue(body, OllamaChatResponse.class);
        String content = null;
        long promptTokens = 0;
        long completionTokens = 0;
        String model = effectiveModel;
        if (resp != null) {
            if (resp.getMessage() != null) content = resp.getMessage().getContent();
            if (resp.getPrompt_eval_count() != null) promptTokens = resp.getPrompt_eval_count();
            if (resp.getEval_count() != null) completionTokens = resp.getEval_count();
            if (resp.getModel() != null && !resp.getModel().isBlank()) model = resp.getModel();
        }
        if (content == null && body != null && !body.isBlank()) {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(body);
            com.fasterxml.jackson.databind.JsonNode messageNode = root.path("message");
            if (!messageNode.isMissingNode()) {
                com.fasterxml.jackson.databind.JsonNode c = messageNode.path("content");
                if (!c.isMissingNode() && !c.isNull()) content = c.asText();
                if (content == null || content.isEmpty()) {
                    com.fasterxml.jackson.databind.JsonNode parts = messageNode.path("parts");
                    if (!parts.isMissingNode() && parts.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (com.fasterxml.jackson.databind.JsonNode part : parts) {
                            com.fasterxml.jackson.databind.JsonNode text = part.path("text");
                            if (!text.isMissingNode() && !text.isNull()) sb.append(text.asText());
                        }
                        if (sb.length() > 0) content = sb.toString();
                    }
                }
            }
        }
        return new ChatResult(content != null ? content : "", promptTokens, completionTokens, model);
    }

    /**
     * Registers this plugin with {@link PluginRegistry} for the default tenant under the given id.
     * For multi-tenant, use {@link #register(String, String)} or register per tenant at startup.
     *
     * @param pluginId id to register under (e.g. "GPT4_EXECUTOR" to match pipeline scope)
     */
    public void register(String pluginId) {
        register("default", pluginId);
    }

    /**
     * Registers this plugin with {@link PluginRegistry} for the given tenant under the given id.
     * Call once per tenant at worker startup (e.g. from bootstrap).
     *
     * @param tenantId tenant id (e.g. from OLO_TENANT_IDS or olo:tenants)
     * @param pluginId id to register under (e.g. "GPT4_EXECUTOR")
     */
    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerModelExecutor(tenantId, pluginId, this);
    }

    /**
     * Convenience: create an Ollama plugin and register it for the default tenant under the given id.
     * For multi-tenant, create the plugin and call {@link #register(String, String)} for each tenant.
     *
     * @param pluginId plugin id (e.g. "GPT4_EXECUTOR")
     * @param baseUrl  Ollama base URL (e.g. "http://localhost:11434"); null for default
     * @param model    model name (e.g. "llama3.2"); null for default
     */
    public static void registerOllamaPlugin(String pluginId, String baseUrl, String model) {
        OllamaModelExecutorPlugin plugin = new OllamaModelExecutorPlugin(baseUrl, model);
        plugin.register(pluginId);
    }
}
