package com.olo.plugin;

/**
 * Plugin contract types aligned with execution tree scope {@code contractType}.
 * Used to register and resolve plugins by capability.
 */
public final class ContractType {

    /** Model executor: prompt → responseText (e.g. LLM chat/completion). */
    public static final String MODEL_EXECUTOR = "MODEL_EXECUTOR";

    /** Embedding: text → embedding vector. */
    public static final String EMBEDDING = "EMBEDDING";

    private ContractType() {
    }
}
