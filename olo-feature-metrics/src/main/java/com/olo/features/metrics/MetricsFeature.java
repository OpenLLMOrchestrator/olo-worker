package com.olo.features.metrics;

import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.annotations.ResourceCleanup;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PluginExecutionResult;
import com.olo.features.PostNodeCall;
import com.olo.features.PreNodeCall;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feature that records node execution metrics (e.g. counter per tenant/nodeType) and,
 * for PLUGIN nodes, detailed modal/plugin metrics: tenantId, pipeline, pluginId, modelId,
 * promptTokens, completionTokens, durationMs, success, timestamp.
 * Uses a lazy, thread-safe holder: on first execution the registry is created via CAS
 * and reused forever. No synchronized blocks; kernel remains untouched.
 */
@OloFeature(name = "metrics", phase = FeaturePhase.PRE_FINALLY, applicableNodeTypes = { "*" })
public final class MetricsFeature implements PreNodeCall, PostNodeCall, ResourceCleanup {

    private static final AtomicReference<MeterRegistry> REGISTRY = new AtomicReference<>();

    /** Plugin output keys for metrics (align with OllamaModelExecutorPlugin.OUTPUT_*). */
    private static final String KEY_PROMPT_TOKENS = "promptTokens";
    private static final String KEY_COMPLETION_TOKENS = "completionTokens";
    private static final String KEY_MODEL_ID = "modelId";

    /**
     * Returns the shared meter registry, creating it on first call (lock-free CAS).
     * Thread-safe; at most one registry is ever created.
     */
    private static MeterRegistry getRegistry() {
        MeterRegistry existing = REGISTRY.get();
        if (existing != null) {
            return existing;
        }
        MeterRegistry created = new SimpleMeterRegistry();
        if (REGISTRY.compareAndSet(null, created)) {
            return created;
        }
        return REGISTRY.get();
    }

    @Override
    public void before(NodeExecutionContext ctx) {
        // No timing in context; PluginInvoker measures at execution boundary
    }

    @Override
    public void after(NodeExecutionContext ctx, Object result) {
        MeterRegistry registry = getRegistry();
        String tenant = nullToUnknown(ctx.getTenantId());
        String nodeType = ctx.getNodeType() != null && !ctx.getNodeType().isBlank() ? ctx.getNodeType() : ctx.getType();
        if (nodeType == null || nodeType.isBlank()) nodeType = "unknown";

        registry.counter("olo.node.executions",
                "tenant", tenant,
                "nodeType", nodeType
        ).increment();

        if ("PLUGIN".equals(ctx.getType()) && ctx.getPluginId() != null && !ctx.getPluginId().isBlank()) {
            recordPluginMetrics(registry, ctx, result);
        }
    }

    /**
     * Whether to include modelId as a tag (can explode cardinality if model names are dynamic).
     * Tenant config: metrics.includeModelTag = true/false. Default false for safety.
     */
    private boolean isIncludeModelTag(NodeExecutionContext ctx) {
        Object metrics = ctx.getTenantConfigMap() != null ? ctx.getTenantConfigMap().get("metrics") : null;
        if (!(metrics instanceof Map)) return false;
        Object v = ((Map<?, ?>) metrics).get("includeModelTag");
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equalsIgnoreCase(v.toString());
    }

    private void recordPluginMetrics(MeterRegistry registry, NodeExecutionContext ctx, Object result) {
        String tenantId = nullToUnknown(ctx.getTenantId());
        String pipeline = ctx.getQueueName() != null && !ctx.getQueueName().isBlank() ? ctx.getQueueName() : "unknown";
        String pluginIdStr = nullToUnknown(ctx.getPluginId());
        boolean includeModelTag = isIncludeModelTag(ctx);

        long durationMs = 0;
        boolean success = ctx.isExecutionSucceeded();
        Map<String, Object> outputs = null;

        if (result instanceof PluginExecutionResult) {
            PluginExecutionResult pr = (PluginExecutionResult) result;
            durationMs = pr.getDurationMs();
            success = pr.isSuccess();
            outputs = pr.getOutputs();
        } else if (result instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            outputs = map;
            success = ctx.isExecutionSucceeded();
        }

        long promptTokens = 0;
        long completionTokens = 0;
        String modelId = "unknown";
        if (outputs != null) {
            promptTokens = toLong(outputs.get(KEY_PROMPT_TOKENS), 0);
            completionTokens = toLong(outputs.get(KEY_COMPLETION_TOKENS), 0);
            Object m = outputs.get(KEY_MODEL_ID);
            if (m != null && m.toString() != null && !m.toString().isBlank()) modelId = m.toString().trim();
        }

        Timer.Builder timerBuilder = Timer.builder("olo.plugin.execution")
                .tag("tenant", tenantId)
                .tag("pipeline", pipeline)
                .tag("pluginId", pluginIdStr)
                .tag("success", String.valueOf(success));
        if (includeModelTag) {
            timerBuilder.tag("modelId", modelId);
        }
        timerBuilder.register(registry).record(durationMs, TimeUnit.MILLISECONDS);

        if (promptTokens > 0) {
            if (includeModelTag) {
                registry.counter("olo.plugin.prompt_tokens",
                        "tenant", tenantId, "pipeline", pipeline, "pluginId", pluginIdStr, "modelId", modelId)
                        .increment(promptTokens);
            } else {
                registry.counter("olo.plugin.prompt_tokens",
                        "tenant", tenantId, "pipeline", pipeline, "pluginId", pluginIdStr)
                        .increment(promptTokens);
            }
        }
        if (completionTokens > 0) {
            if (includeModelTag) {
                registry.counter("olo.plugin.completion_tokens",
                        "tenant", tenantId, "pipeline", pipeline, "pluginId", pluginIdStr, "modelId", modelId)
                        .increment(completionTokens);
            } else {
                registry.counter("olo.plugin.completion_tokens",
                        "tenant", tenantId, "pipeline", pipeline, "pluginId", pluginIdStr)
                        .increment(completionTokens);
            }
        }
    }

    private static String nullToUnknown(String s) {
        return s != null && !s.isBlank() ? s : "unknown";
    }

    private static long toLong(Object o, long defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public void onExit() {
        // Optional: close or clear registry on shutdown if needed
    }
}
