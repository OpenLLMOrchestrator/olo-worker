package com.olo.executiontree.tree;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Structural type of an execution tree node. JSON (pipeline config) uses the enum name as string;
 * unknown values deserialize as {@link #UNKNOWN}.
 *
 * @see ExecutionTreeNode#getType()
 * @see <a href="../../../docs/node-type-catalog.md">Node type catalog</a>
 */
public enum NodeType {
    SEQUENCE,
    IF,
    SWITCH,
    CASE,
    ITERATOR,
    FORK,
    JOIN,
    PLUGIN,
    TRY_CATCH,
    RETRY,
    SUB_PIPELINE,
    EVENT_WAIT,
    LLM_DECISION,
    TOOL_ROUTER,
    EVALUATION,
    REFLECTION,
    /** Used when config contains an unknown type string. */
    UNKNOWN;

    @JsonValue
    public String toValue() {
        return name();
    }

    @JsonCreator
    public static NodeType fromValue(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        String normalized = value.trim().toUpperCase();
        for (NodeType t : values()) {
            if (t != UNKNOWN && t.name().equals(normalized)) return t;
        }
        return UNKNOWN;
    }

    /** Returns the string name for this type (never null for known types; "UNKNOWN" for UNKNOWN). */
    public String getTypeName() {
        return name();
    }
}
