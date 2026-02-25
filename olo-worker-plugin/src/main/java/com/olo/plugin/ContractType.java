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

    /** Vector store: upsert/query/delete vectors (e.g. Qdrant). */
    public static final String VECTOR_STORE = "VECTOR_STORE";

    /** Image generation: prompt + options → image (e.g. Stable Diffusion, ComfyUI, InvokeAI). */
    public static final String IMAGE_GENERATOR = "IMAGE_GENERATOR";

    /**
     * Reducer: combines outputs from multiple plugins (e.g. model responses) into a single formatted output.
     * Inputs: map of source label → value (e.g. "X Model" → "xyz", "Y Model" → "abc").
     * Output: typically "combinedOutput" → e.g. "Output From X Model:\"xyz\"\nOutput From Y Model:\"abc\"".
     */
    public static final String REDUCER = "REDUCER";

    private ContractType() {
    }
}
