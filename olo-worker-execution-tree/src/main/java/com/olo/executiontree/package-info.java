/**
 * Pipeline execution tree: contract/configuration and JSON serialization.
 *
 * <ul>
 *   <li>{@link com.olo.executiontree.config} – root {@link com.olo.executiontree.config.PipelineConfiguration}
 *       and {@link com.olo.executiontree.config.PipelineDefinition}</li>
 *   <li>{@link com.olo.executiontree.defaults} – root-level execution defaults (engine, temporal, activity)</li>
 *   <li>{@link com.olo.executiontree.inputcontract} – input contract and parameter definitions</li>
 *   <li>{@link com.olo.executiontree.outputcontract} – output contract (final result) and result mapping</li>
 *   <li>{@link com.olo.executiontree.variableregistry} – variable registry entries and scope</li>
 *   <li>{@link com.olo.executiontree.scope} – scope plugins and features</li>
 *   <li>{@link com.olo.executiontree.tree} – execution tree nodes and mappings</li>
 *   <li>{@link com.olo.executiontree.load} – {@link com.olo.executiontree.load.ConfigurationLoader#loadConfiguration},
 *       {@link com.olo.executiontree.load.GlobalConfigurationContext} (Redis → DB → file → default, then retry)</li>
 *   <li>{@link com.olo.executiontree.ExecutionTreeConfig} – {@code fromJson}/{@code toJson};
 *       {@link com.olo.executiontree.ExecutionTreeConfig#ensureUniqueNodeIds ensureUniqueNodeIds} (fill missing node ids with UUID);
 *       {@link com.olo.executiontree.ExecutionTreeConfig#refreshAllNodeIds refreshAllNodeIds} (replace all node ids with new UUIDs before writing to Redis/DB)</li>
 * </ul>
 */
package com.olo.executiontree;
