package com.olo.plugin;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-scoped registry of plugins by tenant id and plugin id. Register implementations
 * (e.g. {@link ModelExecutorPlugin}) per tenant so the worker can resolve {@code pluginRef}
 * from execution tree nodes and invoke the plugin with input/output mappings.
 */
public final class PluginRegistry {

    private static final PluginRegistry INSTANCE = new PluginRegistry();

    /** tenantId → (pluginId → PluginEntry) */
    private final Map<String, Map<String, PluginEntry>> pluginsByTenant = new ConcurrentHashMap<>();

    public static PluginRegistry getInstance() {
        return INSTANCE;
    }

    private PluginRegistry() {
    }

    /**
     * Registers a model-executor plugin for the given tenant under the given id.
     *
     * @param tenantId tenant id (e.g. from OLO_TENANT_IDS or olo:tenants)
     * @param id       plugin id (must match scope plugin id and tree node pluginRef)
     * @param plugin   implementation
     * @throws IllegalArgumentException if tenantId or id is blank, or plugin already registered for this tenant
     */
    public void registerModelExecutor(String tenantId, String id, ModelExecutorPlugin plugin) {
        register(tenantId, id, ContractType.MODEL_EXECUTOR, "1.0", plugin);
    }

    /**
     * Registers a plugin for the given tenant under the given id and contract type.
     *
     * @param tenantId    tenant id
     * @param id          plugin id
     * @param contractType {@link ContractType#MODEL_EXECUTOR} or {@link ContractType#EMBEDDING}
     * @param plugin      implementation (e.g. {@link ModelExecutorPlugin})
     */
    public void register(String tenantId, String id, String contractType, Object plugin) {
        register(tenantId, id, contractType, null, plugin);
    }

    /**
     * Registers a plugin for the given tenant with an explicit contract version.
     *
     * @param tenantId        tenant id
     * @param id              plugin id
     * @param contractType    contract type
     * @param contractVersion contract version (e.g. 1.0); null = unknown (version check skipped)
     * @param plugin          implementation
     */
    public void register(String tenantId, String id, String contractType, String contractVersion, Object plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String tid = normalize(tenantId);
        String pid = Objects.requireNonNull(id, "id").trim();
        if (pid.isEmpty()) {
            throw new IllegalArgumentException("Plugin id must be non-blank");
        }
        Map<String, PluginEntry> byId = pluginsByTenant.computeIfAbsent(tid, k -> new ConcurrentHashMap<>());
        PluginEntry entry = new PluginEntry(pid, contractType, contractVersion, plugin);
        if (byId.putIfAbsent(pid, entry) != null) {
            throw new IllegalArgumentException("Plugin already registered for tenant " + tid + ": " + id);
        }
    }

    private static String normalize(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return "default";
        return tenantId.trim();
    }

    /**
     * Returns the plugin entry for the given tenant and plugin id, or null if not registered.
     *
     * @param tenantId tenant id
     * @param pluginId plugin id
     * @return entry or null
     */
    public PluginEntry get(String tenantId, String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return null;
        Map<String, PluginEntry> byId = pluginsByTenant.get(normalize(tenantId));
        return byId != null ? byId.get(pluginId.trim()) : null;
    }

    /**
     * Returns the model-executor plugin for the given tenant and id, or null if not registered or not a model executor.
     */
    public ModelExecutorPlugin getModelExecutor(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        if (e == null || !ContractType.MODEL_EXECUTOR.equals(e.getContractType())) {
            return null;
        }
        Object p = e.getPlugin();
        return p instanceof ModelExecutorPlugin ? (ModelExecutorPlugin) p : null;
    }

    /** Returns the contract version for the plugin in the given tenant, or null if unknown. */
    public String getContractVersion(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        return e != null ? e.getContractVersion() : null;
    }

    /** Returns the full structure: tenant id → (plugin id → entry). For iteration and shutdown. */
    public Map<String, Map<String, PluginEntry>> getAllByTenant() {
        return Collections.unmodifiableMap(pluginsByTenant);
    }

    /** Removes all registrations (mainly for tests). */
    public void clear() {
        pluginsByTenant.clear();
    }

    /**
     * Registered plugin: id, contract type, and implementation instance.
     */
    public static final class PluginEntry {
        private final String id;
        private final String contractType;
        private final String contractVersion;
        private final Object plugin;

        PluginEntry(String id, String contractType, String contractVersion, Object plugin) {
            this.id = id;
            this.contractType = contractType;
            this.contractVersion = contractVersion;
            this.plugin = plugin;
        }

        public String getId() {
            return id;
        }

        public String getContractType() {
            return contractType;
        }

        /** Contract version (e.g. 1.0) for config compatibility; null = unknown. */
        public String getContractVersion() {
            return contractVersion;
        }

        public Object getPlugin() {
            return plugin;
        }
    }
}
