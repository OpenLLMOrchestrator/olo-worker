/**
 * Tool contract for OLO: tools are plugins with discovery metadata (description, category)
 * for planner demos, multi-strategy pipelines, and agent-style orchestration.
 * <p>
 * Implement {@link ToolProvider} and register via {@link com.olo.internal.tools.InternalTools}
 * (internal) or as a community plugin. The execution tree references tools by {@code pluginRef}
 * (same as plugins); tools are registered with {@link com.olo.plugin.PluginRegistry} per tenant.
 */
package com.olo.tools;
