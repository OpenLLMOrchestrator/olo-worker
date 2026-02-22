package com.olo.worker.workflow;

import com.olo.input.model.WorkflowInput;
import com.olo.worker.activity.OloKernelActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * OLO Kernel workflow implementation. Caches input, then runs the execution tree
 * (using a local copy of the pipeline config) and returns the workflow result.
 * The tree traversal runs SEQUENCE children in order and for PLUGIN nodes runs
 * pre features, plugin activity, and post features (e.g. debug logging when on -debug queue).
 */
public class OloKernelWorkflowImpl implements OloKernelWorkflow {

    private static final Duration ACTIVITY_SCHEDULE_TO_CLOSE = Duration.ofMinutes(5);

    private final OloKernelActivities activities = Workflow.newActivityStub(
            OloKernelActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(ACTIVITY_SCHEDULE_TO_CLOSE)
                    .build()
    );

    @Override
    public String run(WorkflowInput workflowInput) {
        activities.processInput(workflowInput.toJson());
        String queueName = workflowInput.getRouting() != null ? workflowInput.getRouting().getPipeline() : null;
        String result = activities.runExecutionTree(
                queueName != null ? queueName : "",
                workflowInput.toJson());
        return result != null ? result : "";
    }
}
