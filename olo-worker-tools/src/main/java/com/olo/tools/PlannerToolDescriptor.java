package com.olo.tools;

import java.util.Map;

/**
 * Contract for tools to expose metadata to the planner: schema, capabilities, and cost.
 * Used by the planner to build the static {@code plannerDesignContract} (tooling.availableTools).
 * {@link ToolProvider} extends this with default implementations so all tools can contribute.
 */
public interface PlannerToolDescriptor {

    /** Tool id (e.g. MARKET_RESEARCH_MODEL, BUDGET_ESTIMATOR). */
    String getToolId();

    /** Contract type (e.g. MODEL_EXECUTOR, REDUCER). */
    String getContractType();

    /** Human-readable description. */
    String getDescription();

    /** Category name for grouping (RESEARCH, FINANCE, etc.). */
    String getCategoryName();

    /** Input parameter names to schema type (e.g. "prompt" → "STRING"). */
    Map<String, String> getInputSchema();

    /** Output parameter names to schema type (e.g. "responseText" → "STRING"). */
    Map<String, String> getOutputSchema();

    /** Capabilities (e.g. supportsParallel, supportsStreaming). */
    Map<String, Object> getCapabilities();

    /** Estimated cost per invocation (e.g. 0.02). */
    double getEstimatedCost();

    /** Version (e.g. "1.0"). */
    String getVersion();
}
