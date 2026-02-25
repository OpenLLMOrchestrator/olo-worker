/**
 * Aggregator module for internal plugins (Ollama, LiteLLM, Qdrant, Ollama Embedding).
 * No code; only brings these plugins onto the classpath for the worker fat JAR.
 * Registration is done in the worker via {@link com.olo.plugin.PluginManager#registerInternal}.
 */
package com.olo.internal.plugins;
