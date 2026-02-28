package com.olo.planner.a;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Immutable description of a single planner step after normalization.
 */
record JsonPlannerStepInfo(
        String toolId,
        boolean planner,
        Map<String, Object> plannerParams,
        JsonNode input,
        String displayName
) {
}

