package com.olo.plugin.embedding.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.TenantConfig;
import com.olo.plugin.EmbeddingPlugin;
import com.olo.plugin.PluginRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Embedding plugin that calls Ollama /api/embed. Input: "text" or "texts".
 * Output: "embeddings" (List of float[]), "model".
 */
public final class OllamaEmbeddingPlugin implements EmbeddingPlugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    public OllamaEmbeddingPlugin(String baseUrl, String model) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:11434";
        this.model = model != null && !model.isBlank() ? model.trim() : "nomic-embed-text";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public OllamaEmbeddingPlugin() {
        this("http://localhost:11434", "nomic-embed-text");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        List<String> texts = new ArrayList<>();
        if (inputs != null && inputs.containsKey("texts") && inputs.get("texts") instanceof List) {
            for (Object o : (List<?>) inputs.get("texts")) {
                texts.add(Objects.toString(o, ""));
            }
        } else if (inputs != null && inputs.containsKey("text")) {
            texts.add(Objects.toString(inputs.get("text"), ""));
        }
        if (texts.isEmpty()) {
            return Map.of("embeddings", List.of(), "model", model);
        }

        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("ollamaBaseUrl") != null
                ? Objects.toString(tenantConfig.get("ollamaBaseUrl")).trim() : baseUrl;
        String effectiveModel = tenantConfig != null && tenantConfig.get("ollamaEmbeddingModel") != null
                ? Objects.toString(tenantConfig.get("ollamaEmbeddingModel")).trim() : model;

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", effectiveModel);
        reqBody.put("input", texts.size() == 1 ? texts.get(0) : texts);
        String json = MAPPER.writeValueAsString(reqBody);

        URI uri = URI.create(effectiveBaseUrl + "/api/embed");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama embed API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        List<float[]> embeddings = new ArrayList<>();
        JsonNode embNode = root.path("embeddings");
        if (embNode.isArray()) {
            for (JsonNode arr : embNode) {
                if (arr.isArray()) {
                    float[] vec = new float[arr.size()];
                    for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble(0);
                    embeddings.add(vec);
                }
            }
        }
        String outModel = root.path("model").asText(effectiveModel);
        Map<String, Object> out = new HashMap<>();
        out.put("embeddings", embeddings);
        out.put("model", outModel);
        return out;
    }

    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerEmbedding(tenantId, pluginId, this);
    }

    public void register(String pluginId) {
        register("default", pluginId);
    }
}
