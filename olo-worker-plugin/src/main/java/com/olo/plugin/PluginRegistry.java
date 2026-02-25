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

    /** Registers an embedding plugin for the given tenant. */
    public void registerEmbedding(String tenantId, String id, EmbeddingPlugin plugin) {
        register(tenantId, id, ContractType.EMBEDDING, "1.0", plugin);
    }

    /** Registers a vector store plugin for the given tenant. */
    public void registerVectorStore(String tenantId, String id, VectorStorePlugin plugin) {
        register(tenantId, id, ContractType.VECTOR_STORE, "1.0", plugin);
    }

    /** Registers an image generation plugin for the given tenant. */
    public void registerImageGenerator(String tenantId, String id, ImageGenerationPlugin plugin) {
        register(tenantId, id, ContractType.IMAGE_GENERATOR, "1.0", plugin);
    }

    /** Registers a reducer plugin for the given tenant (combines outputs from multiple plugins). */
    public void registerReducer(String tenantId, String id, ReducerPlugin plugin) {
        register(tenantId, id, ContractType.REDUCER, "1.0", plugin);
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
        register(tenantId, id, contractType, contractVersion, null, plugin);
    }

    /**
     * Registers a plugin with optional capability metadata (for audit and future pluginId+version resolution).
     *
     * @param tenantId          tenant id
     * @param id                plugin id
     * @param contractType      contract type
     * @param contractVersion   plugin/contract version (e.g. 1.0); null = unknown
     * @param capabilityMetadata optional map (e.g. supportedOperations, vendor); null = empty
     * @param plugin            implementation
     */
    public void register(String tenantId, String id, String contractType, String contractVersion,
                         Map<String, Object> capabilityMetadata, Object plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String tid = normalize(tenantId);
        String pid = Objects.requireNonNull(id, "id").trim();
        if (pid.isEmpty()) {
            throw new IllegalArgumentException("Plugin id must be non-blank");
        }
        Map<String, PluginEntry> byId = pluginsByTenant.computeIfAbsent(tid, k -> new ConcurrentHashMap<>());
        PluginEntry entry = new PluginEntry(pid, contractType, contractVersion, capabilityMetadata, plugin, null);
        if (byId.putIfAbsent(pid, entry) != null) {
            throw new IllegalArgumentException("Plugin already registered for tenant " + tid + ": " + id);
        }
    }

    /**
     * Registers a plugin via its provider so the registry can create one instance per tree node.
     * Within a run, the same node (same nodeId) receives the same instance; different nodes receive different instances.
     *
     * @param tenantId          tenant id
     * @param id                plugin id
     * @param contractType      contract type
     * @param contractVersion   contract version; null = unknown
     * @param capabilityMetadata optional metadata; null = empty
     * @param provider          provider used to create plugin instances per node
     */
    public void register(String tenantId, String id, String contractType, String contractVersion,
                         Map<String, Object> capabilityMetadata, PluginProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String tid = normalize(tenantId);
        String pid = Objects.requireNonNull(id, "id").trim();
        if (pid.isEmpty()) {
            throw new IllegalArgumentException("Plugin id must be non-blank");
        }
        Map<String, PluginEntry> byId = pluginsByTenant.computeIfAbsent(tid, k -> new ConcurrentHashMap<>());
        PluginEntry entry = new PluginEntry(pid, contractType, contractVersion, capabilityMetadata, null, provider);
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
     * Returns the plugin as {@link ExecutablePlugin} for invocation. All registered plugins implement this interface.
     * Use {@link #getExecutable(String, String, String, Map)} when per-node instances are required (same nodeId → same instance in a run).
     */
    public ExecutablePlugin getExecutable(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        if (e == null) return null;
        Object p = e.getPlugin();
        return p instanceof ExecutablePlugin ? (ExecutablePlugin) p : null;
    }

    /**
     * Returns the plugin instance for the given node. One instance per node in the tree (mounted per node);
     * when the same node runs again (e.g. in a loop), the same instance is returned. {@code cache} is keyed by nodeId
     * and must be run-scoped (e.g. created at the start of execution and passed through).
     *
     * @param tenantId tenant id
     * @param pluginId plugin id
     * @param nodeId   tree node id (unique per node; same nodeId in a run reuses the same instance)
     * @param cache    run-scoped cache nodeId → ExecutablePlugin; must be mutable and non-null
     * @return plugin instance for this node, or null if not registered
     */
    public ExecutablePlugin getExecutable(String tenantId, String pluginId, String nodeId,
                                           Map<String, ExecutablePlugin> cache) {
        if (nodeId == null || cache == null) return getExecutable(tenantId, pluginId);
        PluginEntry e = get(tenantId, pluginId);
        if (e == null) return null;
        if (e.provider != null) {
            ExecutablePlugin cached = cache.get(nodeId);
            if (cached != null) return cached;
            Object p = e.provider.createPlugin();
            ExecutablePlugin plugin = (p instanceof ExecutablePlugin) ? (ExecutablePlugin) p : null;
            if (plugin != null) cache.put(nodeId, plugin);
            return plugin;
        }
        Object p = e.getPlugin();
        return p instanceof ExecutablePlugin ? (ExecutablePlugin) p : null;
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

    /** Returns the embedding plugin for the given tenant and id, or null. */
    public EmbeddingPlugin getEmbedding(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        if (e == null || !ContractType.EMBEDDING.equals(e.getContractType())) return null;
        Object p = e.getPlugin();
        return p instanceof EmbeddingPlugin ? (EmbeddingPlugin) p : null;
    }

    /** Returns the vector store plugin for the given tenant and id, or null. */
    public VectorStorePlugin getVectorStore(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        if (e == null || !ContractType.VECTOR_STORE.equals(e.getContractType())) return null;
        Object p = e.getPlugin();
        return p instanceof VectorStorePlugin ? (VectorStorePlugin) p : null;
    }

    /** Returns the image generation plugin for the given tenant and id, or null. */
    public ImageGenerationPlugin getImageGenerator(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        if (e == null || !ContractType.IMAGE_GENERATOR.equals(e.getContractType())) return null;
        Object p = e.getPlugin();
        return p instanceof ImageGenerationPlugin ? (ImageGenerationPlugin) p : null;
    }

    /** Returns the reducer plugin for the given tenant and id, or null. */
    public ReducerPlugin getReducer(String tenantId, String pluginId) {
        PluginEntry e = get(tenantId, pluginId);
        if (e == null || !ContractType.REDUCER.equals(e.getContractType())) return null;
        Object p = e.getPlugin();
        return p instanceof ReducerPlugin ? (ReducerPlugin) p : null;
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
     * Registered plugin: id, contract type, version, optional capability metadata, and either a singleton
     * instance ({@code plugin}) or a {@link PluginProvider} for per-node instance creation.
     */
    public static final class PluginEntry {
        private final String id;
        private final String contractType;
        private final String contractVersion;
        private final Map<String, Object> capabilityMetadata;
        private final Object plugin;
        private final PluginProvider provider;

        PluginEntry(String id, String contractType, String contractVersion, Object plugin) {
            this(id, contractType, contractVersion, null, plugin, null);
        }

        PluginEntry(String id, String contractType, String contractVersion,
                    Map<String, Object> capabilityMetadata, Object plugin, PluginProvider provider) {
            this.id = id;
            this.contractType = contractType;
            this.contractVersion = contractVersion;
            this.capabilityMetadata = capabilityMetadata != null ? Map.copyOf(capabilityMetadata) : Map.of();
            this.plugin = plugin;
            this.provider = provider;
        }

        public String getId() {
            return id;
        }

        public String getContractType() {
            return contractType;
        }

        /** Plugin/contract version (e.g. 1.0) for compatibility and audit; null = unknown. */
        public String getContractVersion() {
            return contractVersion;
        }

        /** Optional capability metadata (e.g. supportedOperations, vendor); immutable, never null. */
        public Map<String, Object> getCapabilityMetadata() {
            return capabilityMetadata;
        }

        /** Singleton instance, or (when provider != null) provider.getPlugin(). */
        public Object getPlugin() {
            return provider != null ? provider.getPlugin() : plugin;
        }
    }
}
