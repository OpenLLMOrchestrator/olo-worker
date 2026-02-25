package com.olo.plugin;

import com.olo.config.TenantConfig;

import java.util.Map;

/**
 * Contract for an embedding plugin: accepts text(s) and returns embedding vectors.
 * Aligns with {@link ContractType#EMBEDDING}. Invoked via
 * {@link #execute(Map, TenantConfig)} with input "text" or "texts" and output "embeddings".
 * <p>
 * Input map: "text" (String) or "texts" (List&lt;String&gt;).
 * Output map: "embeddings" (List of float[] or List of Double), "model" (String, optional).
 */
public interface EmbeddingPlugin extends ExecutablePlugin {
}
