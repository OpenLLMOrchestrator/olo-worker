package com.olo.worker.workflow;

import com.olo.input.model.InputItem;
import com.olo.input.model.WorkflowInput;
import com.olo.worker.activity.OloKernelActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.Optional;

/**
 * OLO Kernel workflow implementation. Caches input, then runs the model-executor plugin
 * (e.g. Ollama) as an activity and returns the chat response.
 */
public class OloKernelWorkflowImpl implements OloKernelWorkflow {

    private static final Duration ACTIVITY_SCHEDULE_TO_CLOSE = Duration.ofMinutes(5);
    /** Plugin id for chat (matches pipeline scope / execution tree pluginRef, e.g. GPT4_EXECUTOR). */
    private static final String CHAT_PLUGIN_ID = "GPT4_EXECUTOR";
    /** Input parameter name for user message (matches pipeline inputContract / variableRegistry). */
    private static final String USER_QUERY_INPUT = "userQuery";

    private final OloKernelActivities activities = Workflow.newActivityStub(
            OloKernelActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(ACTIVITY_SCHEDULE_TO_CLOSE)
                    .build()
    );

    @Override
    public String run(WorkflowInput workflowInput) {
        activities.processInput(workflowInput.toJson());
        String userQuery = getInputValue(workflowInput, USER_QUERY_INPUT);
        String queueName = workflowInput.getRouting() != null ? workflowInput.getRouting().getPipeline() : null;
        String chatResponse = activities.getChatResponseWithFeatures(
                queueName != null ? queueName : "",
                CHAT_PLUGIN_ID,
                userQuery);
        return chatResponse != null ? chatResponse : "";
    }

    private static String getInputValue(WorkflowInput input, String name) {
        if (input == null || input.getInputs() == null) return "";
        Optional<String> value = input.getInputs().stream()
                .filter(i -> name.equals(i.getName()))
                .map(InputItem::getValue)
                .findFirst();
        return value.orElse("");
    }
}
