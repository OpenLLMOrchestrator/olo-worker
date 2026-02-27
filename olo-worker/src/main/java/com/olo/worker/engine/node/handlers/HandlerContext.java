package com.olo.worker.engine.node.handlers;

import com.olo.executiontree.config.ExecutionType;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricher;
import com.olo.worker.engine.PluginInvoker;

import java.util.concurrent.ExecutorService;

/**
 * Shared context for node handlers: plugin invoker, config, execution type, executor, ledger run id, enricher.
 */
public final class HandlerContext {

    private final PluginInvoker pluginInvoker;
    private final PipelineConfiguration config;
    private final ExecutionType executionType;
    private final ExecutorService executor;
    private final String ledgerRunId;
    private final DynamicNodeBuilder dynamicNodeBuilder;
    private final NodeFeatureEnricher nodeFeatureEnricher;

    public HandlerContext(PluginInvoker pluginInvoker, PipelineConfiguration config,
                          ExecutionType executionType, ExecutorService executor,
                          String ledgerRunId, DynamicNodeBuilder dynamicNodeBuilder,
                          NodeFeatureEnricher nodeFeatureEnricher) {
        this.pluginInvoker = pluginInvoker;
        this.config = config;
        this.executionType = executionType != null ? executionType : ExecutionType.SYNC;
        this.executor = executor;
        this.ledgerRunId = ledgerRunId;
        this.dynamicNodeBuilder = dynamicNodeBuilder;
        this.nodeFeatureEnricher = nodeFeatureEnricher != null ? nodeFeatureEnricher : (n, c) -> n;
    }

    public PluginInvoker getPluginInvoker() { return pluginInvoker; }
    public PipelineConfiguration getConfig() { return config; }
    public ExecutionType getExecutionType() { return executionType; }
    public ExecutorService getExecutor() { return executor; }
    public String getLedgerRunId() { return ledgerRunId; }
    public DynamicNodeBuilder getDynamicNodeBuilder() { return dynamicNodeBuilder; }
    public NodeFeatureEnricher getNodeFeatureEnricher() { return nodeFeatureEnricher; }
}
