package com.olo.plugin.image.comfyui;

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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Image generation plugin for ComfyUI (e.g. aidockorg/comfyui-cuda).
 * Submits a minimal txt2img workflow via POST /prompt, polls /history, then fetches image via /view.
 * Default base URL http://localhost:8188.
 */
public final class ComfyUIImagePlugin implements ImageGenerationPlugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final String checkpointName;
    private final HttpClient httpClient;

    public ComfyUIImagePlugin(String baseUrl, String checkpointName) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:8188";
        this.checkpointName = checkpointName != null && !checkpointName.isBlank() ? checkpointName.trim() : "v1-5-pruned-emaonly.safetensors";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    public ComfyUIImagePlugin() {
        this("http://localhost:8188", "v1-5-pruned-emaonly.safetensors");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("comfyuiBaseUrl") != null
                ? Objects.toString(tenantConfig.get("comfyuiBaseUrl")).trim() : baseUrl;
        String ckpt = tenantConfig != null && tenantConfig.get("comfyuiCheckpoint") != null
                ? Objects.toString(tenantConfig.get("comfyuiCheckpoint")).trim() : checkpointName;

        String prompt = inputs != null ? Objects.toString(inputs.get("prompt"), "").trim() : "";
        String negativePrompt = inputs != null && inputs.containsKey("negativePrompt")
                ? Objects.toString(inputs.get("negativePrompt"), "").trim() : "bad hands";
        int width = inputs != null && inputs.get("width") instanceof Number
                ? ((Number) inputs.get("width")).intValue() : 512;
        int height = inputs != null && inputs.get("height") instanceof Number
                ? ((Number) inputs.get("height")).intValue() : 512;
        int steps = inputs != null && inputs.get("steps") instanceof Number
                ? ((Number) inputs.get("steps")).intValue() : 20;
        long seed = inputs != null && inputs.get("seed") instanceof Number
                ? ((Number) inputs.get("seed")).longValue() : System.currentTimeMillis();

        Map<String, Object> workflow = buildWorkflow(prompt, negativePrompt, width, height, steps, seed, ckpt);
        String promptId = submitPrompt(effectiveBaseUrl, workflow);
        waitForCompletion(effectiveBaseUrl, promptId);
        String imageBase64 = getOutputImage(effectiveBaseUrl, promptId);

        Map<String, Object> out = new HashMap<>();
        out.put("imageBase64", imageBase64 != null ? imageBase64 : "");
        out.put("seed", seed);
        out.put("prompt_id", promptId);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildWorkflow(String prompt, String negativePrompt, int width, int height, int steps, long seed, String ckpt) {
        Map<String, Object> workflow = new HashMap<>();
        workflow.put("3", Map.of(
                "class_type", "KSampler",
                "inputs", Map.of(
                        "cfg", 8,
                        "denoise", 1,
                        "latent_image", List.of("5", 0),
                        "model", List.of("4", 0),
                        "negative", List.of("7", 0),
                        "positive", List.of("6", 0),
                        "sampler_name", "euler",
                        "scheduler", "normal",
                        "seed", seed,
                        "steps", steps
                )
        ));
        workflow.put("4", Map.of(
                "class_type", "CheckpointLoaderSimple",
                "inputs", Map.of("ckpt_name", ckpt)
        ));
        workflow.put("5", Map.of(
                "class_type", "EmptyLatentImage",
                "inputs", Map.of("batch_size", 1, "height", height, "width", width)
        ));
        workflow.put("6", Map.of(
                "class_type", "CLIPTextEncode",
                "inputs", Map.of(
                        "clip", List.of("4", 1),
                        "text", prompt
                )
        ));
        workflow.put("7", Map.of(
                "class_type", "CLIPTextEncode",
                "inputs", Map.of(
                        "clip", List.of("4", 1),
                        "text", negativePrompt
                )
        ));
        workflow.put("8", Map.of(
                "class_type", "VAEDecode",
                "inputs", Map.of(
                        "samples", List.of("3", 0),
                        "vae", List.of("4", 2)
                )
        ));
        workflow.put("9", Map.of(
                "class_type", "SaveImage",
                "inputs", Map.of(
                        "filename_prefix", "ComfyUI",
                        "images", List.of("8", 0)
                )
        ));
        return workflow;
    }

    private String submitPrompt(String base, Map<String, Object> workflow) throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("prompt", workflow));
        URI uri = URI.create(base + "/prompt");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new RuntimeException("ComfyUI submit failed: " + res.statusCode() + " " + res.body());
        }
        JsonNode root = MAPPER.readTree(res.body());
        JsonNode promptIdNode = root.path("prompt_id");
        if (promptIdNode.isMissingNode()) throw new RuntimeException("ComfyUI did not return prompt_id: " + res.body());
        return promptIdNode.asText();
    }

    private void waitForCompletion(String base, String promptId) throws Exception {
        int maxWait = 300;
        int waited = 0;
        while (waited < maxWait) {
            URI uri = URI.create(base + "/history/" + promptId);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) continue;
            JsonNode hist = MAPPER.readTree(res.body());
            if (hist.has(promptId)) {
                JsonNode run = hist.path(promptId);
                JsonNode status = run.path("status");
                boolean completed = !status.isMissingNode() && status.path("completed").asBoolean(false);
                if (completed) return;
            }
            TimeUnit.SECONDS.sleep(1);
            waited++;
        }
        throw new RuntimeException("ComfyUI workflow did not complete within " + maxWait + "s");
    }

    private String getOutputImage(String base, String promptId) throws Exception {
        URI uri = URI.create(base + "/history/" + promptId);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) return "";
        JsonNode hist = MAPPER.readTree(res.body());
        JsonNode run = hist.path(promptId);
        if (run.isMissingNode()) return "";
        JsonNode outputs = run.path("outputs");
        JsonNode node9 = outputs.path("9");
        if (node9.isMissingNode()) return "";
        JsonNode images = node9.path("images");
        if (!images.isArray() || images.size() == 0) return "";
        String filename = images.get(0).path("filename").asText("");
        String subfolder = images.get(0).path("subfolder").asText("");
        String type = images.get(0).path("type").asText("output");
        String viewUrl = base + "/view?filename=" + filename + "&subfolder=" + subfolder + "&type=" + type;
        HttpRequest getReq = HttpRequest.newBuilder(URI.create(viewUrl)).timeout(java.time.Duration.ofSeconds(30)).GET().build();
        HttpResponse<byte[]> imgRes = httpClient.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
        if (imgRes.statusCode() != 200) return "";
        return Base64.getEncoder().encodeToString(imgRes.body());
    }

    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerImageGenerator(tenantId, pluginId, this);
    }

    public void register(String pluginId) {
        register("default", pluginId);
    }
}
