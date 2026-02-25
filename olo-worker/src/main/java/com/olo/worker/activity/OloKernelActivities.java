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
     * Used by tree traversal for PLUGIN nodes (e.g. Ollama); not for direct workflow use.
     *
     * @param pluginId plugin id (e.g. GPT4_EXECUTOR)
     * @param prompt   user prompt / message
     * @return model response text, or empty string if missing
     */
    @ActivityMethod
    String getChatResponse(String pluginId, String prompt);

    /**
     * Runs the execution tree for the given queue using a local (deep) copy of the pipeline config.
     * Resolves effective queue (from param or activity task queue for -debug), creates LocalContext,
     * seeds variable map from workflow input (IN scope), traverses the tree (SEQUENCE → children;
     * PLUGIN → pre features, execute plugin, post features), then applies resultMapping to produce
     * the workflow result. Returns the result as a string (e.g. the single output "answer" for chat).
     *
     * @param queueName         pipeline/task queue name (e.g. olo-chat-queue-oolama or olo-chat-queue-oolama-debug)
     * @param workflowInputJson workflow input JSON (for variable resolution and session)
     * @return workflow result string (e.g. final answer for chat flow)
     */
    @ActivityMethod
    String runExecutionTree(String queueName, String workflowInputJson);

    /**
     * Returns an execution plan JSON when the tree is linear (only SEQUENCE, GROUP, and leaf nodes).
     * Plan contains: linear (true/false), and when true: configJson, pipelineName, queueName,
     * workflowInputJson, nodes (array of {activityType, nodeId}). Activities are leaf nodes (no children) or feature-type nodes;
     * internal nodes are not activity types. The workflow schedules one Temporal activity per leaf/activity.
     *
     * @param queueName         pipeline/task queue name
     * @param workflowInputJson workflow input JSON
     * @return plan JSON string
     */
    @ActivityMethod
    String getExecutionPlan(String queueName, String workflowInputJson);

    /**
     * Applies the pipeline resultMapping to the variable map and returns the workflow result string.
     *
     * @param planJson      JSON string from getExecutionPlan (must have linear=true; used for configJson, pipelineName)
     * @param variableMapJson variable map JSON after per-node execution
     * @return workflow result string
     */
    @ActivityMethod
    String applyResultMapping(String planJson, String variableMapJson);

    /**
     * Executes a single node (for per-node workflow path). Activities are leaf nodes (no children) or feature-type nodes;
     * activity type is "NODETYPE" or "PLUGIN:pluginRef" (e.g. "PLUGIN:GPT4_EXECUTOR"). Temporal event history shows this as "ExecuteNode".
     * When executing a planner-generated step (nodeId not in static pipeline), pass the JSON array of dynamic steps
     * in dynamicStepsJson so the activity can resolve and run that step.
     *
     * @param activityType     logical type for this node (NODETYPE or PLUGIN:pluginRef for leaf/activity nodes)
     * @param planJson         plan JSON from getExecutionPlan
     * @param nodeId            node id to execute
     * @param variableMapJson  current variable map JSON
     * @param queueName        task queue name
     * @param workflowInputJson workflow input JSON
     * @param dynamicStepsJson optional; when non-null, used to resolve nodeId when it is not in the pipeline (e.g. planner steps)
     * @return updated variable map JSON, or for PLANNER a JSON object with variableMapJson and dynamicSteps
     */
    @ActivityMethod
    String executeNode(String activityType, String planJson, String nodeId, String variableMapJson,
                       String queueName, String workflowInputJson, String dynamicStepsJson);
}
