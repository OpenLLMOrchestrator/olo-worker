package com.olo.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.annotations.FeaturePhase;
import com.olo.annotations.OloFeature;
import com.olo.annotations.ResourceCleanup;
import com.olo.features.NodeExecutionContext;
import com.olo.features.PluginExecutionResult;
import com.olo.features.PostNodeCall;
import com.olo.features.PreNodeCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Ledger feature that attaches to every node (internal and leaf). At PRE records node start;
 * at FINALLY records node end (output snapshot, status, error). For MODEL and PLANNER type nodes,
 * extracts AI cost metrics (token counts, model name, provider) from plugin output and persists them.
 */
@OloFeature(name = "ledger-node", phase = FeaturePhase.PRE_FINALLY, applicableNodeTypes = { "*" })
public final class NodeLedgerFeature implements PreNodeCall, PostNodeCall, ResourceCleanup {

    private static final Logger log = LoggerFactory.getLogger(NodeLedgerFeature.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_PROMPT_TOKENS = "promptTokens";
    private static final String KEY_COMPLETION_TOKENS = "completionTokens";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TOP_P = "topP";
    private static final String KEY_SEED = "seed";
    private static final String KEY_PROVIDER_REQUEST_ID = "providerRequestId";
    private static final String KEY_PROMPT_COST = "promptCost";
    private static final String KEY_COMPLETION_COST = "completionCost";
    private static final String KEY_TOTAL_COST = "totalCost";
    private static final String KEY_CURRENCY = "currency";
    private static final String ATTR_ERROR = "error";
    private static final String ATTR_ERROR_CODE = "errorCode";
    private static final String ATTR_STACKTRACE = "stacktrace";

    private final RunLedger runLedger;

    public NodeLedgerFeature(RunLedger runLedger) {
        this.runLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
    }

    @Override
    public void before(NodeExecutionContext context) {
        String runId = LedgerContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        runLedger.nodeStarted(
                runId,
                context.getTenantId() != null && !context.getTenantId().isBlank() ? context.getTenantId() : null,
                context.getNodeId(),
                context.getType() != null ? context.getType() : "UNKNOWN",
                null,
                now
        );
    }

    @Override
    public void after(NodeExecutionContext context, Object nodeResult) {
        String runId = LedgerContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String outputJson = toOutputSnapshot(nodeResult);
        boolean success = context.getExecutionSucceeded() != null && context.isExecutionSucceeded();
        String status = success ? "SUCCESS" : "FAILED";
        String errorMessage = null;
        String errorCode = null;
        String errorDetailsJson = null;
        if (!success && context.getAttributes() != null) {
            Object err = context.getAttributes().get(ATTR_ERROR);
            if (err != null) {
                errorMessage = err instanceof Throwable ? ((Throwable) err).getMessage() : err.toString();
                if (err instanceof Throwable) {
                    errorCode = err.getClass().getName();
                    errorDetailsJson = stackTraceToString((Throwable) err);
                }
            }
            if (context.getAttributes().containsKey(ATTR_ERROR_CODE))
                errorCode = String.valueOf(context.getAttributes().get(ATTR_ERROR_CODE));
            if (context.getAttributes().containsKey(ATTR_STACKTRACE))
                errorDetailsJson = String.valueOf(context.getAttributes().get(ATTR_STACKTRACE));
        }
        NodeAiMetrics aiMetrics = isModelOrPlannerNode(context) ? extractAiMetrics(nodeResult, context) : NodeAiMetrics.none();
        NodeReplayMeta replayMeta = isModelOrPlannerNode(context) ? extractReplayMeta(nodeResult, context) : NodeReplayMeta.none();
        NodeFailureMeta failureMeta = NodeFailureMeta.none();
        if (!success && (errorCode != null || errorDetailsJson != null)) {
            failureMeta = new NodeFailureMeta(null, null, null, errorCode, errorDetailsJson, null, null, null);
        }
        runLedger.nodeEnded(runId, context.getNodeId(), outputJson, now, status, errorMessage, aiMetrics, replayMeta, failureMeta);
    }

    private static String stackTraceToString(Throwable t) {
        if (t == null) return null;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static boolean isModelOrPlannerNode(NodeExecutionContext context) {
        String nodeType = context.getNodeType();
        if (nodeType == null || nodeType.isBlank()) return false;
        String upper = nodeType.toUpperCase();
        return upper.startsWith("MODEL") || upper.startsWith("MODAL") || upper.startsWith("PLANNER");
    }

    private static NodeAiMetrics extractAiMetrics(Object nodeResult, NodeExecutionContext context) {
        Map<String, Object> outputs = null;
        if (nodeResult instanceof PluginExecutionResult) {
            outputs = ((PluginExecutionResult) nodeResult).getOutputs();
        } else if (nodeResult instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) nodeResult;
            outputs = m;
        }
        if (outputs == null || outputs.isEmpty()) return NodeAiMetrics.none();

        int tokenIn = toInt(outputs.get(KEY_PROMPT_TOKENS), 0);
        int tokenOut = toInt(outputs.get(KEY_COMPLETION_TOKENS), 0);
        String modelName = null;
        Object modelObj = outputs.get(KEY_MODEL_ID);
        if (modelObj != null && !modelObj.toString().isBlank()) modelName = modelObj.toString();

        String provider = inferProvider(context);
        BigDecimal estimatedCost = toDecimal(outputs.get(KEY_TOTAL_COST));
        BigDecimal promptCost = toDecimal(outputs.get(KEY_PROMPT_COST));
        BigDecimal completionCost = toDecimal(outputs.get(KEY_COMPLETION_COST));
        BigDecimal totalCost = estimatedCost != null ? estimatedCost : (promptCost != null && completionCost != null ? promptCost.add(completionCost) : null);
        if (totalCost == null && promptCost != null) totalCost = promptCost;
        if (totalCost == null && completionCost != null) totalCost = completionCost;
        String currency = null;
        Object cur = outputs.get(KEY_CURRENCY);
        if (cur != null && !cur.toString().isBlank()) currency = cur.toString();

        if (tokenIn == 0 && tokenOut == 0 && modelName == null && provider == null && totalCost == null && promptCost == null && completionCost == null) {
            return NodeAiMetrics.none();
        }
        return new NodeAiMetrics(tokenIn, tokenOut, estimatedCost != null ? estimatedCost : totalCost, promptCost, completionCost, totalCost, currency, modelName, provider);
    }

    private static NodeReplayMeta extractReplayMeta(Object nodeResult, NodeExecutionContext context) {
        Map<String, Object> outputs = null;
        if (nodeResult instanceof PluginExecutionResult) {
            outputs = ((PluginExecutionResult) nodeResult).getOutputs();
        } else if (nodeResult instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) nodeResult;
            outputs = m;
        }
        if (outputs == null || outputs.isEmpty()) return NodeReplayMeta.none();
        BigDecimal temperature = toDecimal(outputs.get(KEY_TEMPERATURE));
        BigDecimal topP = toDecimal(outputs.get(KEY_TOP_P));
        Long seed = null;
        Object s = outputs.get(KEY_SEED);
        if (s instanceof Number) seed = ((Number) s).longValue();
        else if (s != null) try { seed = Long.parseLong(s.toString()); } catch (NumberFormatException ignored) { }
        String providerRequestId = null;
        Object pr = outputs.get(KEY_PROVIDER_REQUEST_ID);
        if (pr != null && !pr.toString().isBlank()) providerRequestId = pr.toString();
        if (temperature == null && topP == null && seed == null && providerRequestId == null) return NodeReplayMeta.none();
        return new NodeReplayMeta(null, null, null, null, temperature, topP, seed, null, null, providerRequestId);
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String inferProvider(NodeExecutionContext context) {
        if (context.getPluginId() != null && !context.getPluginId().isBlank()) {
            String id = context.getPluginId().toLowerCase();
            if (id.contains("ollama")) return "ollama";
            if (id.contains("openai") || id.contains("gpt")) return "openai";
        }
        return null;
    }

    private static int toInt(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String toOutputSnapshot(Object nodeResult) {
        if (nodeResult == null) {
            return null;
        }
        if (nodeResult instanceof PluginExecutionResult) {
            Map<String, Object> out = ((PluginExecutionResult) nodeResult).getOutputs();
            return toJson(out);
        }
        if (nodeResult instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) nodeResult;
            return toJson(m);
        }
        return nodeResult.toString();
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o != null ? o : "{}");
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    @Override
    public void onExit() {
        // No resources to release
    }
}
