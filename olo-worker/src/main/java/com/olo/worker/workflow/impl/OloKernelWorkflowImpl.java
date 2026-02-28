package com.olo.worker.workflow.impl;

import com.olo.input.model.WorkflowInput;
import com.olo.worker.activity.OloKernelActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;

import java.time.Duration;

/**
 * OLO Kernel workflow: get plan, then run per-step/per-node or fallback to RunExecutionTree.
 * Plan execution (steps, nodes, variable map merge) is delegated to {@link WorkflowPlanExecutor}.
 */
public class OloKernelWorkflowImpl implements com.olo.worker.workflow.OloKernelWorkflow {

    private static final Duration ACTIVITY_SCHEDULE_TO_CLOSE = Duration.ofMinutes(5);
    private static final Duration ACTIVITY_NO_TIMEOUT_DEBUG = Duration.ofDays(365);

    @Override
    public String run(WorkflowInput workflowInput) {
        String taskQueue = null;
        try {
            WorkflowInfo info = Workflow.getInfo();
            if (info != null) taskQueue = info.getTaskQueue();
        } catch (Exception ignored) {
        }
        boolean debugQueue = taskQueue != null && taskQueue.endsWith("-debug");
        Duration activityTimeout = debugQueue ? ACTIVITY_NO_TIMEOUT_DEBUG : ACTIVITY_SCHEDULE_TO_CLOSE;

        OloKernelActivities activities = Workflow.newActivityStub(
                OloKernelActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(activityTimeout).build());
        ActivityStub untypedActivityStub = Workflow.newUntypedActivityStub(
                ActivityOptions.newBuilder().setStartToCloseTimeout(activityTimeout).build());

        activities.processInput(workflowInput.toJson());
        String queueName = workflowInput.getRouting() != null ? workflowInput.getRouting().getPipeline() : null;
        String queueNameOrEmpty = queueName != null ? queueName : "";
        String workflowInputJson = workflowInput.toJson();

        String planJson = activities.getExecutionPlan(queueNameOrEmpty, workflowInputJson);
        if (planJson == null || !planJson.contains("\"linear\":true")) {
            Workflow.getLogger(OloKernelWorkflowImpl.class).info(
                    "Scheduling RunExecutionTree activity: tree is non-linear (plan is null or linear=false)");
            String result = activities.runExecutionTree(queueNameOrEmpty, workflowInputJson);
            return result != null ? result : "";
        }

        try {
            String variableMapJson = WorkflowPlanExecutor.runPlan(
                    planJson, untypedActivityStub, queueNameOrEmpty, workflowInputJson);
            String result = activities.applyResultMapping(planJson, variableMapJson);
            return result != null ? result : "";
        } catch (Exception e) {
            Workflow.getLogger(OloKernelWorkflowImpl.class).warn(
                    "Per-node execution failed, falling back to RunExecutionTree: {}", e.getMessage());
            Workflow.getLogger(OloKernelWorkflowImpl.class).info(
                    "Scheduling RunExecutionTree activity: fallback after per-node execution failure");
            String result = activities.runExecutionTree(queueNameOrEmpty, workflowInputJson);
            return result != null ? result : "";
        }
    }
}
