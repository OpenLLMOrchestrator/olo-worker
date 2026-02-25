/**
 * OLO worker plugin contracts and registry. Plugins implement contracts (e.g. {@link com.olo.plugin.ModelExecutorPlugin})
 * and register with {@link com.olo.plugin.PluginRegistry} per tenant by id so the worker can resolve {@code pluginRef}
 * from execution tree nodes and invoke them with input/output mappings.
 * <ul>
 *   <li>{@link com.olo.plugin.ExecutablePlugin} – base contract: execute(Map, TenantConfig) → Map</li>
 *   <li>{@link com.olo.plugin.PluginProvider} – SPI for pluggable discovery (ServiceLoader)</li>
 *   <li>{@link com.olo.plugin.ContractType} – MODEL_EXECUTOR, EMBEDDING, VECTOR_STORE, IMAGE_GENERATOR, REDUCER</li>
 *   <li>{@link com.olo.plugin.ModelExecutorPlugin}, {@link com.olo.plugin.EmbeddingPlugin}, {@link com.olo.plugin.VectorStorePlugin}, {@link com.olo.plugin.ImageGenerationPlugin}, {@link com.olo.plugin.ReducerPlugin} – extend ExecutablePlugin</li>
 *   <li>{@link com.olo.plugin.PluginManager} – internal registration and community loading from a controlled directory</li>
 *   <li>{@link com.olo.plugin.RestrictedPluginClassLoader} – hardened parent for community JARs (plugin API + slf4j only)</li>
 *   <li>{@link com.olo.plugin.PluginRegistry} – tenant-scoped registration and lookup</li>
 *   <li>{@link com.olo.annotations.ResourceCleanup} – onExit() for shutdown</li>
 * </ul>
 * <p>
 * Evolution path – versioning and capability metadata: {@link com.olo.plugin.PluginProvider#getVersion()} and
 * {@link com.olo.plugin.PluginProvider#getCapabilityMetadata()} support semantic compatibility, multi-team
 * deployments, and enterprise audit. Future extensions may use pluginId+version as a composite key.
 */
package com.olo.plugin;
