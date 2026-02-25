package com.olo.tools;

import com.olo.plugin.ExecutablePlugin;

/**
 * Marker for a plugin that is exposed as a tool for planner demos and agent orchestration.
 * Tools are registered with the same mechanism as plugins (pluginRef in execution tree);
 * {@link ToolProvider} adds description and category for discovery and UI.
 */
public interface Tool extends ExecutablePlugin {
}
