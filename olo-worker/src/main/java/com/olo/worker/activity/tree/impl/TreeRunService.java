package com.olo.worker.activity.tree.impl;

import com.olo.config.OloConfig;
import com.olo.config.OloSessionCache;
import com.olo.input.model.WorkflowInput;
import com.olo.ledger.RunLedger;
import com.olo.plugin.PluginExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/** Coordinate full execution tree run (context resolver + engine invoker + ledger). */
public final class TreeRunService {

    private static final Logger log = LoggerFactory.getLogger(TreeRunService.class);

    private final Set<String> allowedTenantIds;
    private final OloSessionCache sessionCache;
    private final RunLedger runLedger;
    private final PluginExecutorFactory pluginExecutorFactory;
    private final com.olo.node.DynamicNodeBuilder dynamicNodeBuilder;
    private final com.olo.node.NodeFeatureEnricher nodeFeatureEnricher;

    public TreeRunService(Set<String> allowedTenantIds, OloSessionCache sessionCache, RunLedger runLedger,
                          PluginExecutorFactory pluginExecutorFactory,
                          com.olo.node.DynamicNodeBuilder dynamicNodeBuilder,
                          com.olo.node.NodeFeatureEnricher nodeFeatureEnricher) {
        this.allowedTenantIds = allowedTenantIds != null ? allowedTenantIds : Set.of();
        this.sessionCache = sessionCache;
        this.runLedger = runLedger;
        this.pluginExecutorFactory = pluginExecutorFactory;
        this.dynamicNodeBuilder = dynamicNodeBuilder;
        this.nodeFeatureEnricher = nodeFeatureEnricher;
    }

    public String runExecutionTree(String queueName, String workflowInputJson) {
        int inputLen = workflowInputJson != null ? workflowInputJson.length() : 0;
        String inputSnippet = workflowInputJson != null && workflowInputJson.length() > 300 ? workflowInputJson.substring(0, 300) + "...[truncated]" : (workflowInputJson != null ? workflowInputJson : "");
        log.info("Activity entry | runExecutionTree | queue={} | workflowInputLength={} | workflowInputSnippet={}", queueName != null ? queueName : "", inputLen, inputSnippet);
        WorkflowInput workflowInput;
        try { workflowInput = WorkflowInput.fromJson(workflowInputJson); } catch (Exception e) {
            log.warn("Invalid workflow input JSON for runExecutionTree", e);
            return "";
        }
        String tenantId = OloConfig.normalizeTenantId(workflowInput.getContext() != null ? workflowInput.getContext().getTenantId() : null);
        log.info("RunExecutionTree activity started | invoked from workflow | queue={} | tenantId={}", queueName != null ? queueName : "", tenantId);
        if (!allowedTenantIds.isEmpty() && !allowedTenantIds.contains(tenantId)) throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        sessionCache.incrActiveWorkflows(tenantId);
        try { return doRunExecutionTree(tenantId, queueName, workflowInput, workflowInputJson); }
        finally { sessionCache.decrActiveWorkflows(tenantId); }
    }

    private String doRunExecutionTree(String tenantId, String queueName, WorkflowInput workflowInput, String workflowInputJson) {
        TreeContextResolver.ResolvedContext ctx = TreeContextResolver.resolve(tenantId, queueName, workflowInput);
        if (ctx.status == TreeContextResolver.Status.NO_CONFIG || ctx.status == TreeContextResolver.Status.NO_PIPELINE) return "";
        TreeRunLedger.LedgerRunContext ledgerCtx = TreeRunLedger.startRun(ctx, runLedger, workflowInputJson);
        String runResult = null;
        String runStatus = "FAILED";
        Throwable runFailure = null;
        try {
            runResult = TreeEngineInvoker.run(ctx, pluginExecutorFactory, dynamicNodeBuilder, nodeFeatureEnricher);
            runStatus = "SUCCESS";
            log.info("Execution tree exit | transactionId={} | status={} | resultLength={}", ctx.transactionId, runStatus, runResult != null ? runResult.length() : 0);
            return runResult != null ? runResult : "";
        } catch (IllegalArgumentException e) {
            log.warn("Execution engine validation failed: {}", e.getMessage());
            runFailure = e;
            return "";
        } catch (Throwable t) {
            runFailure = t;
            log.info("Execution tree exit | transactionId={} | status=FAILED | error={}", ctx.transactionId, t.getMessage());
            throw t;
        } finally {
            TreeRunLedger.endRun(ctx, ledgerCtx, runResult, runStatus, runFailure);
        }
    }
}
