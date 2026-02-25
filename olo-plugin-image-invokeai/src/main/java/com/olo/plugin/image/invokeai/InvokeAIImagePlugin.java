package com.olo.plugin.image.invokeai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.TenantConfig;
import com.olo.plugin.ImageGenerationPlugin;
import com.olo.plugin.PluginRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Image generation plugin for InvokeAI (default http://localhost:9090).
 * Uses the InvokeAI REST API for text-to-image. Endpoint and request shape may vary by InvokeAI version;
 * tenant config "invokeaiBaseUrl" and "invokeaiApiPath" can override. Default path: /api/v1/generate.
 */
public final class InvokeAIImagePlugin implements ImageGenerationPlugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final String apiPath;
    private final HttpClient httpClient;

    public InvokeAIImagePlugin(String baseUrl, String apiPath) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:9090";
        this.apiPath = apiPath != null && !apiPath.isBlank() ? apiPath.trim() : "/api/v1/generate";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    public InvokeAIImagePlugin() {
        this("http://localhost:9090", "/api/v1/generate");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("invokeaiBaseUrl") != null
                ? Objects.toString(tenantConfig.get("invokeaiBaseUrl")).trim() : baseUrl;
        String path = tenantConfig != null && tenantConfig.get("invokeaiApiPath") != null
                ? Objects.toString(tenantConfig.get("invokeaiApiPath")).trim() : apiPath;

        String prompt = inputs != null ? Objects.toString(inputs.get("prompt"), "").trim() : "";
        String negativePrompt = inputs != null && inputs.containsKey("negativePrompt")
                ? Objects.toString(inputs.get("negativePrompt"), "").trim() : "";
        int width = inputs != null && inputs.get("width") instanceof Number
                ? ((Number) inputs.get("width")).intValue() : 512;
        int height = inputs != null && inputs.get("height") instanceof Number
                ? ((Number) inputs.get("height")).intValue() : 512;
        int steps = inputs != null && inputs.get("steps") instanceof Number
                ? ((Number) inputs.get("steps")).intValue() : 20;
        long seed = inputs != null && inputs.get("seed") instanceof Number
                ? ((Number) inputs.get("seed")).longValue() : -1L;

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", prompt);
        if (!negativePrompt.isEmpty()) body.put("negative_prompt", negativePrompt);
        body.put("width", width);
        body.put("height", height);
        body.put("steps", steps);
        if (seed >= 0) body.put("seed", seed);

        String json = MAPPER.writeValueAsString(body);
        String url = effectiveBaseUrl.replaceAll("/$", "") + path;
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("InvokeAI API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        String imageBase64 = "";
        long outSeed = seed;
        if (!root.path("image").isMissingNode()) {
            imageBase64 = root.path("image").asText("");
        } else if (root.path("images").isArray() && root.path("images").size() > 0) {
            imageBase64 = root.path("images").get(0).asText("");
        } else if (!root.path("base64").isMissingNode()) {
            imageBase64 = root.path("base64").asText("");
        }
        if (!root.path("seed").isMissingNode()) outSeed = root.path("seed").asLong(-1);

        Map<String, Object> out = new HashMap<>();
        out.put("imageBase64", imageBase64);
        out.put("seed", outSeed);
        if (!root.path("image_url").isMissingNode()) out.put("imageUrl", root.path("image_url").asText());
        return out;
    }

    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerImageGenerator(tenantId, pluginId, this);
    }

    public void register(String pluginId) {
        register("default", pluginId);
    }
}
