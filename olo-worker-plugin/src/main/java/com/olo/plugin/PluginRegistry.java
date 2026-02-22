package com.olo.plugin;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of plugins by id and contract type. Register implementations
 * (e.g. {@link ModelExecutorPlugin}) so the worker can resolve {@code pluginRef}
 * from execution tree nodes and invoke the plugin with input/output mappings.
 */
public final class PluginRegistry {

    private static final PluginRegistry INSTANCE = new PluginRegistry();

    private final Map<String, PluginEntry> byId = new ConcurrentHashMap<>();

    public static PluginRegistry getInstance() {
        return INSTANCE;
    }

    private PluginRegistry() {
    }

    /**
     * Registers a model-executor plugin under the given id.
     *
     * @param id     plugin id (must match scope plugin id and tree node pluginRef)
     * @param plugin implementation
     * @throws IllegalArgumentException if id is blank or already registered
     */
    public void registerModelExecutor(String id, ModelExecutorPlugin plugin) {
        register(id, ContractType.MODEL_EXECUTOR, plugin);
    }

    /**
     * Registers a plugin under the given id and contract type.
     *
     * @param id          plugin id
     * @param contractType {@link ContractType#MODEL_EXECUTOR} or {@link ContractType#EMBEDDING}
     * @param plugin      implementation (e.g. {@link ModelExecutorPlugin})
     * @throws IllegalArgumentException if id is blank or already registered
     */
    public void register(String id, String contractType, Object plugin) {
        Objects.requireNonNull(plugin, "plugin");
        String tid = Objects.requireNonNull(id, "id").trim();
        if (tid.isEmpty()) {
            throw new IllegalArgumentException("Plugin id must be non-blank");
        }
        PluginEntry entry = new PluginEntry(tid, contractType, plugin);
        if (byId.putIfAbsent(tid, entry) != null) {
            throw new IllegalArgumentException("Plugin already registered: " + id);
        }
    }

    /**
     * Returns the plugin entry for the given id, or null if not registered.
     */
    public PluginEntry get(String id) {
        return id == null ? null : byId.get(id.trim());
    }

    /**
     * Returns the model-executor plugin for the given id, or null if not registered or not a model executor.
     */
    public ModelExecutorPlugin getModelExecutor(String id) {
        PluginEntry e = get(id);
        if (e == null || !ContractType.MODEL_EXECUTOR.equals(e.getContractType())) {
            return null;
        }
        Object p = e.getPlugin();
        return p instanceof ModelExecutorPlugin ? (ModelExecutorPlugin) p : null;
    }

    public Map<String, PluginEntry> getAll() {
        return Collections.unmodifiableMap(byId);
    }

    /** Removes all registrations (mainly for tests). */
    public void clear() {
        byId.clear();
    }

    /**
     * Registered plugin: id, contract type, and implementation instance.
     */
    public static final class PluginEntry {
        private final String id;
        private final String contractType;
        private final Object plugin;

        PluginEntry(String id, String contractType, Object plugin) {
            this.id = id;
            this.contractType = contractType;
            this.plugin = plugin;
        }

        public String getId() {
            return id;
        }

        public String getContractType() {
            return contractType;
        }

        public Object getPlugin() {
            return plugin;
        }
    }
}
