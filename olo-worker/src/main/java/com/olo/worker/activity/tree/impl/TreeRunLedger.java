package com.olo.worker.activity.tree.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.config.PipelineDefinition;
import com.olo.executiontree.tree.ExecutionTreeNode;
import com.olo.executiontree.tree.NodeType;
import com.olo.ledger.LedgerContext;
import com.olo.ledger.NoOpLedgerStore;
import com.olo.ledger.RunLedger;
import com.olo.internal.features.InternalFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeMap;

final class TreeRunLedger {

    private static final Logger log = LoggerFactory.getLogger(TreeRunLedger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final class LedgerRunContext {
        final RunLedger runLedger;
        final long ledgerStartTime;

        LedgerRunContext(RunLedger runLedger, long ledgerStartTime) {
            this.runLedger = runLedger;
            this.ledgerStartTime = ledgerStartTime;
        }
    }

    static LedgerRunContext startRun(TreeContextResolver.ResolvedContext ctx, RunLedger runLedger, String workflowInputJson) {
        RunLedger effectiveRunLedger = runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore());
        if (runLedger == null) log.info("Run ledger was null; using no-op ledger so runId is set and node ledger features can run.");
        long ledgerStartTime = System.currentTimeMillis();
        LedgerContext.setRunId(ctx.runId);
        String pluginVersionsJson = buildPluginVersionsJson(ctx.config);
        String configTreeJson = null;
        String tenantConfigJson = null;
        try {
            configTreeJson = MAPPER.writeValueAsString(ctx.pipeline);
            tenantConfigJson = MAPPER.writeValueAsString(ctx.tenantConfigMap);
        } catch (Exception ignored) { }
        effectiveRunLedger.runStarted(ctx.runId, ctx.tenantId, ctx.effectiveQueue, ctx.snapshotVersionId, ctx.snapshotVersionId, pluginVersionsJson, workflowInputJson, ledgerStartTime, null, null, configTreeJson, tenantConfigJson);
        return new LedgerRunContext(effectiveRunLedger, ledgerStartTime);
    }

    static void endRun(TreeContextResolver.ResolvedContext ctx, LedgerRunContext ledgerCtx, String runResult, String runStatus, Throwable runFailure) {
        String runIdForEnd = LedgerContext.getRunId();
        if (runIdForEnd == null) runIdForEnd = ctx.runId;
        if (runIdForEnd != null) {
            long endTime = System.currentTimeMillis();
            Long durationMs = ledgerCtx.ledgerStartTime > 0 ? (endTime - ledgerCtx.ledgerStartTime) : null;
            String errMsg = runFailure != null ? (runFailure.getMessage() != null ? runFailure.getMessage() : runFailure.getClass().getName()) : null;
            String failureStage = runFailure != null ? runFailure.getClass().getSimpleName() : null;
            ledgerCtx.runLedger.runEnded(runIdForEnd, endTime, runResult, runStatus, durationMs, errMsg, failureStage, null, null, "USD");
        }
        LedgerContext.clear();
        InternalFeatures.clearLedgerForRun();
    }

    private static String buildPluginVersionsJson(PipelineConfiguration config) {
        Map<String, String> versions = new TreeMap<>();
        if (config != null && config.getPipelines() != null) {
            for (PipelineDefinition def : config.getPipelines().values()) {
                if (def == null || def.getExecutionTree() == null) continue;
                collectPluginRefs(def.getExecutionTree(), versions);
            }
        }
        try { return MAPPER.writeValueAsString(versions); } catch (Exception e) { return "{}"; }
    }

    private static void collectPluginRefs(ExecutionTreeNode node, Map<String, String> out) {
        if (node == null) return;
        if (node.getType() == NodeType.PLUGIN && node.getPluginRef() != null && !node.getPluginRef().isBlank()) {
            out.putIfAbsent(node.getPluginRef(), "?");
        }
        if (node.getChildren() != null) {
            for (ExecutionTreeNode child : node.getChildren()) collectPluginRefs(child, out);
        }
    }
}
