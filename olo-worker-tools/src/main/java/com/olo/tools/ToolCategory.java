package com.olo.tools;

/**
 * Category for tool discovery and demo grouping (multi-strategy planner, router, research agent, etc.).
 */
public enum ToolCategory {

    /** Planner / strategy generation (e.g. STRATEGY_PLANNER, INTENT_CLASSIFIER). */
    PLANNER,

    /** Research / retrieval (e.g. RESEARCH_TOOL, WEB_RESEARCH, MARKET_RESEARCH_MODEL). */
    RESEARCH,

    /** Critique / review (e.g. CRITIC_TOOL, CODE_REVIEWER, FACT_CHECKER). */
    CRITIC,

    /** Evaluation / scoring (e.g. EVALUATOR_MODEL, CONFIDENCE_SCORER, SCORER_MODEL). */
    EVALUATOR,

    /** Reducer / merge (e.g. OUTPUT_REDUCER). */
    REDUCER,

    /** Code / execution (e.g. CODE_GENERATOR, PYTHON_EXECUTOR, DOCKERFILE_GENERATOR). */
    CODE,

    /** Data / analytics (e.g. DATA_SUMMARIZER, ANOMALY_DETECTOR, CHART_RENDERER). */
    DATA,

    /** Image / visualization (e.g. IMAGE_GENERATOR, CHART_GENERATOR). */
    IMAGE,

    /** Other / custom. */
    OTHER
}
