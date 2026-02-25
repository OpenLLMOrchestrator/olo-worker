package com.olo.plugin.litellm;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Model-executor plugin that calls an OpenAI-compatible API (e.g. LiteLLM proxy in front of Ollama).
 * Uses POST /v1/chat/completions. Default base URL http://localhost:4000 (LiteLLM).
 * Input: "prompt". Output: "responseText", "promptTokens", "completionTokens", "modelId".
 */
@OloPlugin(
        id = "LITELLM_EXECUTOR",
        displayName = "LiteLLM (OpenAI-compatible)",
        contractType = "MODEL_EXECUTOR",
        description = "Calls OpenAI-compatible /v1/chat/completions (e.g. LiteLLM over Ollama)",
        category = "Model",
        inputParameters = { @OloPluginParam(name = "prompt", type = "STRING", required = true) },
        outputParameters = { @OloPluginParam(name = "responseText", type = "STRING", required = false) }
)
public final class LiteLLMModelExecutorPlugin implements ModelExecutorPlugin, ResourceCleanup {

    private static final String INPUT_PROMPT = "prompt";
    private static final String OUTPUT_RESPONSE_TEXT = "responseText";
    public static final String OUTPUT_PROMPT_TOKENS = "promptTokens";
    public static final String OUTPUT_COMPLETION_TOKENS = "completionTokens";
    public static final String OUTPUT_MODEL = "modelId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    public LiteLLMModelExecutorPlugin(String baseUrl, String model) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:4000";
        this.model = model != null && !model.isBlank() ? model.trim() : "ollama/llama3.2";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public LiteLLMModelExecutorPlugin() {
        this("http://localhost:4000", "ollama/llama3.2");
    }

    @Override
    public void onExit() {
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        Object promptObj = inputs == null ? null : inputs.get(INPUT_PROMPT);
        String prompt = promptObj != null ? Objects.toString(promptObj).trim() : "";
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("litellmBaseUrl") != null
                ? Objects.toString(tenantConfig.get("litellmBaseUrl")).trim() : baseUrl;
        String effectiveModel = tenantConfig != null && tenantConfig.get("litellmModel") != null
                ? Objects.toString(tenantConfig.get("litellmModel")).trim() : model;

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", effectiveModel);
        reqBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        String json = MAPPER.writeValueAsString(reqBody);

        URI uri = URI.create(effectiveBaseUrl + "/v1/chat/completions");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("LiteLLM API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        String content = "";
        long promptTokens = 0;
        long completionTokens = 0;
        String modelId = effectiveModel;

        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).path("message");
            if (!msg.path("content").isMissingNode()) content = msg.path("content").asText("");
        }
        if (!root.path("usage").isMissingNode()) {
            promptTokens = root.path("usage").path("prompt_tokens").asLong(0);
            completionTokens = root.path("usage").path("completion_tokens").asLong(0);
        }
        if (!root.path("model").isMissingNode()) modelId = root.path("model").asText(effectiveModel);

        Map<String, Object> out = new HashMap<>();
        out.put(OUTPUT_RESPONSE_TEXT, content);
        out.put(OUTPUT_PROMPT_TOKENS, promptTokens);
        out.put(OUTPUT_COMPLETION_TOKENS, completionTokens);
        out.put(OUTPUT_MODEL, modelId);
        return out;
    }

    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerModelExecutor(tenantId, pluginId, this);
    }

    public void register(String pluginId) {
        register("default", pluginId);
    }
}
