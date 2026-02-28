package com.olo.worker.activity.tree.impl;

import com.olo.executioncontext.ExecutionConfigSnapshot;
import com.olo.worker.engine.ExecutionEngine;
import com.olo.plugin.PluginExecutorFactory;

final class TreeEngineInvoker {

    static String run(TreeContextResolver.ResolvedContext ctx,
                      PluginExecutorFactory pluginExecutorFactory,
                      com.olo.node.DynamicNodeBuilder dynamicNodeBuilder,
                      com.olo.node.NodeFeatureEnricher nodeFeatureEnricher) {
        var executor = pluginExecutorFactory.create(ctx.tenantId, ctx.nodeInstanceCache);
        return ExecutionEngine.run(ctx.snapshot, ctx.inputValues, executor, ctx.tenantConfigMap, dynamicNodeBuilder, nodeFeatureEnricher);
    }
}
