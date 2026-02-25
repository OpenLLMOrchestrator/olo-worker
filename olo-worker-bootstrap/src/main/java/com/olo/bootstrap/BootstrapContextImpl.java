package com.olo.bootstrap;

import com.olo.config.OloConfig;
import com.olo.executiontree.config.PipelineConfiguration;
import com.olo.executiontree.defaults.ExecutionDefaults;
import com.olo.executiontree.defaults.TemporalDefaults;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link BootstrapContext}. Holds env-derived configuration,
 * pipeline configs by "tenant:queue", and mutable contributor data.
 */
public final class BootstrapContextImpl implements BootstrapContext {

    private final OloConfig config;
    private final List<String> taskQueues;
    private final List<String> tenantIds;
    private final Map<String, PipelineConfiguration> pipelineConfigByQueue;
    private final Map<String, Object> contributorData = new LinkedHashMap<>();

    public BootstrapContextImpl(
            OloConfig config,
            Map<String, PipelineConfiguration> pipelineConfigByQueue,
            List<String> tenantIds) {
        this.config = Objects.requireNonNull(config, "config");
        this.taskQueues = config.getTaskQueues();
        this.tenantIds = tenantIds != null ? List.copyOf(tenantIds) : List.of();
        this.pipelineConfigByQueue = pipelineConfigByQueue == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(pipelineConfigByQueue);
    }

    @Deprecated
    public BootstrapContextImpl(OloConfig config, Map<String, PipelineConfiguration> pipelineConfigByQueue) {
        this(config, pipelineConfigByQueue, null);
    }

    @Override
    public OloConfig getConfig() {
        return config;
    }

    @Override
    public List<String> getTaskQueues() {
        return taskQueues;
    }

    @Override
    public List<String> getTenantIds() {
        return tenantIds;
    }

    @Override
    public Map<String, PipelineConfiguration> getPipelineConfigByQueue() {
        return pipelineConfigByQueue;
    }

    @Override
    public String getTemporalTargetOrDefault(String defaultTarget) {
        for (PipelineConfiguration pc : pipelineConfigByQueue.values()) {
            ExecutionDefaults ed = pc != null ? pc.getExecutionDefaults() : null;
            TemporalDefaults td = ed != null ? ed.getTemporal() : null;
            if (td != null && td.getTarget() != null && !td.getTarget().isBlank()) {
                return td.getTarget().trim();
            }
        }
        return defaultTarget != null ? defaultTarget : "localhost:7233";
    }

    @Override
    public String getTemporalNamespaceOrDefault(String defaultNamespace) {
        for (PipelineConfiguration pc : pipelineConfigByQueue.values()) {
            ExecutionDefaults ed = pc != null ? pc.getExecutionDefaults() : null;
            TemporalDefaults td = ed != null ? ed.getTemporal() : null;
            if (td != null && td.getNamespace() != null && !td.getNamespace().isBlank()) {
                return td.getNamespace().trim();
            }
        }
        return defaultNamespace != null ? defaultNamespace : "default";
    }

    @Override
    public void putContributorData(String key, Object value) {
        if (key != null && !key.isBlank()) {
            contributorData.put(key, value);
        }
    }

    @Override
    public Object getContributorData(String key) {
        return contributorData.get(key);
    }

    @Override
    public Map<String, Object> getContributorData() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(contributorData));
    }
}
