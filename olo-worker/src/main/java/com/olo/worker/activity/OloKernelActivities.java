package com.olo.worker.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** OLO Kernel activities. Process workflow input (e.g. resolve values, call external services). */
@ActivityInterface
public interface OloKernelActivities {

    /** Processes the workflow input JSON (e.g. deserialize, store to session, run kernel logic). */
    @ActivityMethod
    String processInput(String workflowInputJson);

    /**
     * Executes a plugin by id with the given inputs JSON (map as JSON string).
     * Returns the plugin outputs as JSON string (e.g. {"responseText":"..."}).
     *
     * @param pluginId   plugin id (e.g. GPT4_EXECUTOR)
     * @param inputsJson JSON object string (e.g. {"prompt":"user message"})
     * @return JSON object string of outputs (e.g. {"responseText":"model response"})
     */
    @ActivityMethod
    String executePlugin(String pluginId, String inputsJson);

    /**
     * Convenience: calls a model-executor plugin with a single "prompt" input and returns the "responseText" output.
     * Used for chat flows (e.g. Ollama plugin).
     *
     * @param pluginId plugin id (e.g. GPT4_EXECUTOR)
     * @param prompt   user prompt / message
     * @return model response text, or empty string if missing
     */
    @ActivityMethod
    String getChatResponse(String pluginId, String prompt);

    /**
     * Same as {@link #getChatResponse(String, String)} but when {@code queueName} ends with "-debug",
     * runs pre/post features (e.g. debug logging) for the pipeline's plugin node before and after the call.
     * Uses pipeline config from global context and {@code queueName} to resolve features.
     *
     * @param queueName pipeline/task queue name (e.g. olo-chat-queue-oolama-debug for debug logs)
     * @param pluginId  plugin id (e.g. GPT4_EXECUTOR)
     * @param prompt    user prompt / message
     * @return model response text, or empty string if missing
     */
    @ActivityMethod
    String getChatResponseWithFeatures(String queueName, String pluginId, String prompt);
}
