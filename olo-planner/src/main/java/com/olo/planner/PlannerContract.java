package com.olo.planner;

/**
 * Contract for the planner: bootstrap keys, template placeholder, and subtree building.
 * Implementations (e.g. in {@code olo-planner-a}) produce the static planner design contract
 * and prompt template at bootstrap; at runtime a PLANNER node injects the user query,
 * calls the LLM (e.g. Llama), and uses a {@link SubtreeBuilder} to build and run a dynamic subtree.
 */
public final class PlannerContract {

    private PlannerContract() {
    }

    /** Key for the planner design contract JSON on the bootstrap context. */
    public static final String KEY_PLANNER_DESIGN_CONTRACT = "plannerDesignContract";

    /** Key for the planner prompt template (with {@link #USER_QUERY_PLACEHOLDER}) on the bootstrap context. */
    public static final String KEY_PLANNER_PROMPT_TEMPLATE = "plannerPromptTemplate";

    /** Placeholder in the prompt template to be replaced with the user query at runtime. */
    public static final String USER_QUERY_PLACEHOLDER = "{{userQuery}}";
}
