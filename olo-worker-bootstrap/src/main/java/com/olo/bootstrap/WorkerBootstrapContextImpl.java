package com.olo.bootstrap;

import com.olo.annotations.ResourceCleanup;
import com.olo.bootstrap.node.DefaultNodeFeatureEnricherFactory;
import com.olo.bootstrap.node.PipelineDynamicNodeBuilder;
import com.olo.config.OloConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.features.FeatureRegistry;
import com.olo.node.DynamicNodeBuilder;
import com.olo.node.NodeFeatureEnricherFactory;
import com.olo.plugin.PluginExecutorFactory;
import com.olo.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of {@link WorkerBootstrapContext}: delegates to a {@link BootstrapContextImpl}
 * and adds run ledger, session cache, plugin executor factory, and resource cleanup for the worker.
 */
public final class WorkerBootstrapContextImpl implements WorkerBootstrapContext {

    private static final Logger log = LoggerFactory.getLogger(WorkerBootstrapContextImpl.class);

    private final BootstrapContextImpl delegate;
    private final Object runLedger;
    private final Object sessionCache;
    private final PluginExecutorFactory pluginExecutorFactory;
    private final DynamicNodeBuilder dynamicNodeBuilder;
    private final NodeFeatureEnricherFactory nodeFeatureEnricherFactory;

    public WorkerBootstrapContextImpl(BootstrapContextImpl delegate, Object runLedger, Object sessionCache,
                                       PluginExecutorFactory pluginExecutorFactory,
                                       DynamicNodeBuilder dynamicNodeBuilder,
                                       NodeFeatureEnricherFactory nodeFeatureEnricherFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.runLedger = runLedger;
        this.sessionCache = Objects.requireNonNull(sessionCache, "sessionCache");
        this.pluginExecutorFactory = Objects.requireNonNull(pluginExecutorFactory, "pluginExecutorFactory");
        this.dynamicNodeBuilder = dynamicNodeBuilder != null ? dynamicNodeBuilder : PipelineDynamicNodeBuilder.getInstance();
        this.nodeFeatureEnricherFactory = nodeFeatureEnricherFactory != null ? nodeFeatureEnricherFactory : DefaultNodeFeatureEnricherFactory.getInstance();
    }

    @Override
    public Object getRunLedger() {
        return runLedger;
    }

    @Override
    public Object getSessionCache() {
        return sessionCache;
    }

    @Override
    public PluginExecutorFactory getPluginExecutorFactory() {
        return pluginExecutorFactory;
    }

    @Override
    public DynamicNodeBuilder getDynamicNodeBuilder() {
        return dynamicNodeBuilder;
    }

    @Override
    public NodeFeatureEnricherFactory getNodeFeatureEnricherFactory() {
        return nodeFeatureEnricherFactory;
    }

    @Override
    public void runResourceCleanup() {
        for (Map<String, PluginRegistry.PluginEntry> byId : PluginRegistry.getInstance().getAllByTenant().values()) {
            for (PluginRegistry.PluginEntry e : byId.values()) {
                Object p = e.getPlugin();
                if (p instanceof ResourceCleanup) {
                    try {
                        ((ResourceCleanup) p).onExit();
                    } catch (Exception ex) {
                        log.warn("Plugin {} onExit failed: {}", e.getId(), ex.getMessage());
                    }
                }
            }
        }
        for (FeatureRegistry.FeatureEntry e : FeatureRegistry.getInstance().getAll().values()) {
            Object inst = e.getInstance();
            if (inst instanceof ResourceCleanup) {
                try {
                    ((ResourceCleanup) inst).onExit();
                } catch (Exception ex) {
                    log.warn("Feature {} onExit failed: {}", e.getName(), ex.getMessage());
                }
            }
        }
    }

    @Override
    public OloConfig getConfig() {
        return delegate.getConfig();
    }

    @Override
    public List<String> getTaskQueues() {
        return delegate.getTaskQueues();
    }

    @Override
    public List<String> getTenantIds() {
        return delegate.getTenantIds();
    }

    @Override
    public Map<String, PipelineConfiguration> getPipelineConfigByQueue() {
        return delegate.getPipelineConfigByQueue();
    }

    @Override
    public String getTemporalTargetOrDefault(String defaultTarget) {
        return delegate.getTemporalTargetOrDefault(defaultTarget);
    }

    @Override
    public String getTemporalNamespaceOrDefault(String defaultNamespace) {
        return delegate.getTemporalNamespaceOrDefault(defaultNamespace);
    }

    @Override
    public void putContributorData(String key, Object value) {
        delegate.putContributorData(key, value);
    }

    @Override
    public Object getContributorData(String key) {
        return delegate.getContributorData(key);
    }

    @Override
    public Map<String, Object> getContributorData() {
        return delegate.getContributorData();
    }
}
