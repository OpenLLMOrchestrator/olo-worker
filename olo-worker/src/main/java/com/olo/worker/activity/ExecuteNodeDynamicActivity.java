package com.olo.worker.activity;

import com.olo.worker.activity.impl.OloKernelActivitiesImpl;
import io.temporal.activity.Activity;
import io.temporal.activity.DynamicActivity;
import io.temporal.common.converter.EncodedValues;

/** Handles per-node activity invocations; tasks for unknown types are dispatched here. */
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
        String dynamicStepsJson;
        try {
            dynamicStepsJson = args.get(5, String.class);
        } catch (Exception e) {
            dynamicStepsJson = null;
        }
        return delegate.executeNode(activityType, planJson, nodeId, variableMapJson,
                queueName != null ? queueName : "", workflowInputJson, dynamicStepsJson);
    }
}
