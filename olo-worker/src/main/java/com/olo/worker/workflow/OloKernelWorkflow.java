package com.olo.worker.workflow;

import com.olo.input.model.WorkflowInput;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * OLO Kernel workflow. Receives workflow input and orchestrates processing.
 */
@WorkflowInterface
public interface OloKernelWorkflow {

    /**
     * Runs the kernel workflow with the given workflow input.
     * Accepts {@link WorkflowInput} so Temporal can deserialize the start payload
     * when the client sends a JSON object (not a JSON string).
     *
     * @param workflowInput workflow input (version, inputs, context, routing, metadata)
     * @return result summary (e.g. transaction id or status)
     */
    @WorkflowMethod
    String run(WorkflowInput workflowInput);
}
