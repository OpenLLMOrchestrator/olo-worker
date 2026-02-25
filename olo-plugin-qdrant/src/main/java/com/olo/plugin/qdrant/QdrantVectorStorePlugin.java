package com.olo.plugin.qdrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.config.TenantConfig;
import com.olo.plugin.PluginRegistry;
import com.olo.plugin.VectorStorePlugin;

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
 * Vector store plugin that uses Qdrant REST API (default http://localhost:6333).
 * Operations: create_collection, upsert, query, delete.
 */
public final class QdrantVectorStorePlugin implements VectorStorePlugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OP = "operation";
    private static final String COLLECTION = "collection";
    private static final String DIMENSION = "dimension";
    private static final String POINTS = "points";
    private static final String VECTOR = "vector";
    private static final String LIMIT = "limit";
    private static final String RESULTS = "results";

    private final String baseUrl;
    private final HttpClient httpClient;

    public QdrantVectorStorePlugin(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "http://localhost:6333";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public QdrantVectorStorePlugin() {
        this("http://localhost:6333");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs, TenantConfig tenantConfig) throws Exception {
        String effectiveBaseUrl = tenantConfig != null && tenantConfig.get("qdrantBaseUrl") != null
                ? Objects.toString(tenantConfig.get("qdrantBaseUrl")).trim() : baseUrl;
        String op = inputs != null ? Objects.toString(inputs.get(OP), "").trim() : "";
        String collection = inputs != null ? Objects.toString(inputs.get(COLLECTION), "").trim() : "";
        if (collection.isEmpty()) {
            throw new IllegalArgumentException("Missing 'collection' in inputs");
        }
        switch (op) {
            case "create_collection":
                int dim = inputs.get(DIMENSION) instanceof Number
                        ? ((Number) inputs.get(DIMENSION)).intValue() : 384;
                createCollection(effectiveBaseUrl, collection, dim);
                return Map.of("ok", true);
            case "upsert":
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> points = inputs.get(POINTS) instanceof List
                        ? (List<Map<String, Object>>) inputs.get(POINTS) : List.of();
                upsert(effectiveBaseUrl, collection, points);
                return Map.of("ok", true);
            case "query":
                Object vecObj = inputs.get(VECTOR);
                float[] vector = toFloatArray(vecObj);
                int limit = inputs.get(LIMIT) instanceof Number
                        ? ((Number) inputs.get(LIMIT)).intValue() : 10;
                List<Map<String, Object>> results = query(effectiveBaseUrl, collection, vector, limit);
                return Map.of(RESULTS, results);
            case "delete":
                Object idsObj = inputs.get("ids");
                delete(effectiveBaseUrl, collection, idsObj);
                return Map.of("ok", true);
            default:
                throw new IllegalArgumentException("Unknown operation: " + op + " (use create_collection, upsert, query, delete)");
        }
    }

    private static float[] toFloatArray(Object vecObj) {
        if (vecObj instanceof float[]) return (float[]) vecObj;
        if (vecObj instanceof double[]) {
            double[] d = (double[]) vecObj;
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
            return f;
        }
        if (vecObj instanceof List) {
            List<?> list = (List<?>) vecObj;
            float[] f = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object x = list.get(i);
                f[i] = x instanceof Number ? ((Number) x).floatValue() : 0f;
            }
            return f;
        }
        throw new IllegalArgumentException("'vector' must be float[], double[], or List<Number>");
    }

    private void createCollection(String base, String collectionName, int dimension) throws Exception {
        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", dimension, "distance", "Cosine")
        );
        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(base + "/collections/" + collectionName);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 200 || res.statusCode() == 201) return;
        if (res.statusCode() == 409) return; // already exists
        throw new RuntimeException("Qdrant create collection failed: " + res.statusCode() + " " + res.body());
    }

    private void upsert(String base, String collectionName, List<Map<String, Object>> points) throws Exception {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Map<String, Object> p : points) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", p.get("id"));
            point.put("vector", p.get("vector"));
            if (p.containsKey("payload")) point.put("payload", p.get("payload"));
            payload.add(point);
        }
        Map<String, Object> body = Map.of("points", payload);
        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(base + "/collections/" + collectionName + "/points?wait=true");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new RuntimeException("Qdrant upsert failed: " + res.statusCode() + " " + res.body());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> query(String base, String collectionName, float[] vector, int limit) throws Exception {
        Map<String, Object> body = Map.of(
                "vector", vector,
                "limit", limit,
                "with_payload", true
        );
        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(base + "/collections/" + collectionName + "/points/query");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new RuntimeException("Qdrant query failed: " + res.statusCode() + " " + res.body());
        }
        Map<String, Object> parsed = MAPPER.readValue(res.body(), Map.class);
        Object result = parsed.get("result");
        if (result instanceof List) return (List<Map<String, Object>>) result;
        return List.of();
    }

    private void delete(String base, String collectionName, Object idsObj) throws Exception {
        Map<String, Object> body;
        if (idsObj instanceof List) {
            body = Map.of("points", idsObj);
        } else {
            body = Map.of("points", List.of(idsObj));
        }
        String json = MAPPER.writeValueAsString(body);
        URI uri = URI.create(base + "/collections/" + collectionName + "/points/delete?wait=true");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new RuntimeException("Qdrant delete failed: " + res.statusCode() + " " + res.body());
        }
    }

    public void register(String tenantId, String pluginId) {
        PluginRegistry.getInstance().registerVectorStore(tenantId, pluginId, this);
    }

    public void register(String pluginId) {
        register("default", pluginId);
    }
}
