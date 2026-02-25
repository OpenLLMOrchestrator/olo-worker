package com.olo.worker.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.DynamicActivity;
import io.temporal.common.converter.EncodedValues;

/**
 * Handles per-node activity invocations so Temporal event history shows
 * the activity type as "NODETYPE" or "PLUGIN:pluginRef" (e.g. "PLUGIN:GPT4_EXECUTOR").
 * Activities are leaf nodes (no children) or feature-type nodes; internal nodes are not.
 * The workflow schedules each node via {@link io.temporal.workflow.ActivityStub#execute(String, Class, Object...)}
 * with the activity type as the first argument; tasks for unknown types are dispatched here.
 */
public final class ExecuteNodeDynamicActivity implements DynamicActivity {

    private final OloKernelActivitiesImpl delegate;

    public ExecuteNodeDynamicActivity(OloKernelActivitiesImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object execute(EncodedValues args) {
        String activityType = Activity.getExecutionContext().getInfo().getActivityType();
        String planJson = args.get(0, String.class);
        String nodeId = args.get(1, String.class);
        String variableMapJson = args.get(2, String.class);
        String queueName = args.get(3, String.class);
        String workflowInputJson = args.get(4, String.class);
        return delegate.executeNode(activityType, planJson, nodeId, variableMapJson,
                queueName != null ? queueName : "", workflowInputJson);
    }
}
