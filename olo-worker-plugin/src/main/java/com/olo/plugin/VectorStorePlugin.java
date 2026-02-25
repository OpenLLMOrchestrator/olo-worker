package com.olo.plugin;

import com.olo.config.TenantConfig;

import java.util.Map;

/**
 * Contract for a vector store plugin (e.g. Qdrant): upsert vectors, query by vector, delete.
 * Aligns with {@link ContractType#VECTOR_STORE}. Invoked via
 * {@link #execute(Map, TenantConfig)} with "operation" and operation-specific payloads.
 * <p>
 * Input map: "operation" ("upsert"|"query"|"delete"|"create_collection"), "collection",
 * and operation-specific keys (points, vector, limit, filter, dimension, etc.).
 * Output map is operation-specific (e.g. "results" for query).
 */
public interface VectorStorePlugin extends ExecutablePlugin {
}
