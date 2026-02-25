# OLO Tools and Demo Use Cases

Tools are **plugins with discovery metadata** (description, category) for planner demos, multi-strategy pipelines, and agent-style orchestration. Each tool is a separate module under `olo-tool-*`; internal tools are aggregated in **olo-internal-tools** and registered with the same **PluginRegistry** as plugins (referenced by `pluginRef` in the execution tree).

## Module layout

| Module | Purpose |
|--------|--------|
| **olo-worker-tools** | Contract: `Tool` (extends ExecutablePlugin), `ToolProvider` (extends PluginProvider), `ToolCategory`. |
| **olo-internal-tools** | Registers internal tool providers with `PluginManager` via `InternalTools.registerInternalTools(pluginManager)`. Called from the worker after `InternalPlugins.createPluginManager()`. |
| **olo-tool-*** | One module per tool (e.g. `olo-tool-research`, `olo-tool-critic`, `olo-tool-evaluator`). Each provides a `ToolProvider` and a `Tool` (typically a MODEL_EXECUTOR that delegates to Ollama or another model with a role-specific prompt). |

## Current internal tools

- **RESEARCH_TOOL** — Research assistant (MODEL_EXECUTOR); delegates to Ollama with research role. Use in multi-strategy planner and research-agent demos.
- **CRITIC_TOOL** — Critical reviewer (MODEL_EXECUTOR); delegates to Ollama with critic role. Use in reflection-based planner and quality-gating demos.
- **EVALUATOR_MODEL** — Evaluator (MODEL_EXECUTOR); delegates to Ollama with evaluator role. Use in multi-strategy planner (choose best response) and scoring demos.

**OUTPUT_REDUCER** is a plugin (olo-join-reducer), not a tool module; use it in JOIN nodes with `mergeStrategy: REDUCE` and `pluginRef: OUTPUT_REDUCER`.

## Demo use cases (planning)

1. **Multi-Strategy Response Planner** — User question → Planner → FORK (Strategy A/B/C) → JOIN (REDUCE → OUTPUT_REDUCER) → EVALUATOR_MODEL (choose best). Tools: STRATEGY_PLANNER (model), RESEARCH_TOOL, CRITIC_TOOL, OUTPUT_REDUCER, EVALUATOR_MODEL.
2. **Tool-Selecting Planner (Router)** — Planner chooses tool by intent (INTENT_CLASSIFIER, MATH_SOLVER, WEB_RESEARCH, CODE_GENERATOR, IMAGE_GENERATOR). Add `olo-tool-*` modules per tool and register in olo-internal-tools.
3. **Business Workflow Planner** — Market research, competitor analysis, persona, budget, plan formatter; JOIN → REDUCE → final plan. Tools: MARKET_RESEARCH_MODEL, COMPETITOR_ANALYZER, PERSONA_BUILDER, BUDGET_ESTIMATOR, PLAN_FORMATTER.
4. **Research Agent with Verification** — PRIMARY_MODEL, FACT_CHECKER, CONFIDENCE_SCORER, RETRY_HANDLER; IF + RETRY nodes.
5. **Code Generation Planner** — ARCHITECT_MODEL, CODE_GENERATOR, TEST_GENERATOR, DOCKERFILE_GENERATOR, CODE_REVIEWER; REDUCE → OUTPUT_REDUCER.
6. **Data Analysis Planner** — DATA_SUMMARIZER, ANOMALY_DETECTOR, PYTHON_EXECUTOR, CHART_RENDERER, INSIGHT_GENERATOR; ITERATOR + variable engine.
7. **Reflection-Based Planner** — DRAFT_MODEL, CRITIC_MODEL, REFINER_MODEL, SCORER_MODEL; iterative improvement pipeline.

To add a new tool: create `olo-tool-<name>`, implement `Tool` and `ToolProvider`, add the provider to `InternalTools.registerInternalTools()` in olo-internal-tools, and add the module to olo-internal-tools `build.gradle` and to `settings.gradle`.

## Execution shape (example: multi-strategy)

```
User Input
   ↓
Planner (break into strategies)
   ↓
FORK
   ├── Strategy A (pluginRef: RESEARCH_TOOL or model)
   ├── Strategy B (pluginRef: RESEARCH_TOOL or model)
   └── Strategy C (pluginRef: RESEARCH_TOOL or model)
   ↓
JOIN (mergeStrategy: REDUCE, pluginRef: OUTPUT_REDUCER)
   ↓
Evaluator (pluginRef: EVALUATOR_MODEL)
```

Configure pipeline scope with `plugins` entries for RESEARCH_TOOL, CRITIC_TOOL, EVALUATOR_MODEL, OUTPUT_REDUCER and reference them in the execution tree by `pluginRef`.
