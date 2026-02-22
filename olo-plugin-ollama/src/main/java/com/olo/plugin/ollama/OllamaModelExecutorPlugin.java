package com.olo.plugin.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.annotations.OloPlugin;
import com.olo.annotations.OloPluginParam;
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
public final class OllamaModelExecutorPlugin implements ModelExecutorPlugin {

    private static final String INPUT_PROMPT = "prompt";
    private static final String OUTPUT_RESPONSE_TEXT = "responseText";

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
    public Map<String, Object> execute(Map<String, Object> inputs) throws Exception {
        Object promptObj = inputs == null ? null : inputs.get(INPUT_PROMPT);
        String prompt = promptObj != null ? Objects.toString(promptObj).trim() : "";
        String responseText = callOllamaChat(prompt);
        Map<String, Object> out = new HashMap<>();
        out.put(OUTPUT_RESPONSE_TEXT, responseText);
        return out;
    }

    private String callOllamaChat(String prompt) throws Exception {
        OllamaChatRequest.Message msg = new OllamaChatRequest.Message("user", prompt);
        OllamaChatRequest req = new OllamaChatRequest(model, List.of(msg), false);
        String json = MAPPER.writeValueAsString(req);
        URI uri = URI.create(baseUrl + "/api/chat");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode() + " " + response.body());
        }
        OllamaChatResponse resp = MAPPER.readValue(response.body(), OllamaChatResponse.class);
        return resp.getMessage() != null && resp.getMessage().getContent() != null
                ? resp.getMessage().getContent() : "";
    }

    /**
     * Registers this plugin with {@link PluginRegistry} under the given id.
     * Call once at worker startup (e.g. from bootstrap).
     *
     * @param pluginId id to register under (e.g. "GPT4_EXECUTOR" to match pipeline scope)
     */
    public void register(String pluginId) {
        PluginRegistry.getInstance().registerModelExecutor(pluginId, this);
    }

    /**
     * Convenience: create an Ollama plugin and register it under the given id.
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
