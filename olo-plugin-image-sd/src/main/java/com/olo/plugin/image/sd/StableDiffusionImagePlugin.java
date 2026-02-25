package com.olo.plugin.image.sd;

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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Image generation plugin for Stable Diffusion WebUI (e.g. aidockorg/stable-diffusion-webui-cuda).
 * Uses POST /sdapi/v1/txt2img. Default base URL http://localhost:7860.
 * Input: prompt, negativePrompt, width, height, steps, seed. Output: imageBase64, imageUrl (optional), seed.
 */
public final class StableDiffusionImagePlugin implements ImageGenerationPlugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final HttpClient httpClient;

    public StableDiffusionImagePlugin(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:7860";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public StableDiffusionImagePlugin() {
        this("http://localhost:7860");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("sdBaseUrl") != null
                ? Objects.toString(tenantConfig.get("sdBaseUrl")).trim() : baseUrl;

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
        body.put("negative_prompt", negativePrompt);
        body.put("width", width);
        body.put("height", height);
        body.put("steps", steps);
        body.put("seed", seed >= 0 ? seed : null);

        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(effectiveBaseUrl + "/sdapi/v1/txt2img");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Stable Diffusion API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode images = root.path("images");
        String imageBase64 = images.isArray() && images.size() > 0 ? images.get(0).asText("") : "";
        Object info = root.path("info").isMissingNode() ? null : root.path("info").toString();
        long outSeed = seed;
        if (info != null && info.toString().contains("\"seed\"")) {
            try {
                JsonNode infoNode = MAPPER.readTree(info.toString());
                if (!infoNode.path("seed").isMissingNode()) outSeed = infoNode.path("seed").asLong(-1);
            } catch (Exception ignored) { }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("imageBase64", imageBase64);
        out.put("seed", outSeed);
        return out;
    }

    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerImageGenerator(tenantId, pluginId, this);
    }

    public void register(String pluginId) {
        register("default", pluginId);
    }
}
