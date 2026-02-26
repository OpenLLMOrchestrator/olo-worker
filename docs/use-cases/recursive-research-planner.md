# Use Case: Recursive Research Planner (Planner Spawns Planner)

## Goal

User asks: **"Create a detailed investment report for Tesla."**

We use:

- **Root** → **Master Planner**
- **Master Planner** expands to: Research Profile (tool) + **Financial Sub-Planner** + **Risk Sub-Planner**
- **Financial Sub-Planner** expands only when executed → Revenue, Profit, Debt analysis steps
- **Risk Sub-Planner** expands only when executed → Market volatility, Regulatory risk, Competitor analysis steps

Tree growth is **lazy and contextual**: planner nodes attach children only when they are executed.

## Execution Philosophy

- A **planner node** is an executable unit: when executed it calls the LLM, parses the plan, and **attaches children** to the tree.
- Execution continues **depth-first** (or strategy-based). Sub-planners are not expanded until execution reaches them.
- No upfront full expansion: the tree shape is not known at workflow start.

## Initial Tree (Start)

At workflow start the pipeline defines:

```
ROOT (SEQUENCE)
 └── MASTER_PLANNER
```

Nothing else exists yet. Pipeline config: `config/olo-recursive-research.json` (initial tree: root → MASTER_PLANNER, `children: []`).

## Step 1 — Execute MASTER_PLANNER

MASTER_PLANNER runs (LLM + parser). It decides it needs:

1. Company profile (tool)
2. Financial analysis (complex → sub-planner)
3. Risk analysis (complex → sub-planner)

The planner output (JSON array) includes one **PLUGIN** step and two **PLANNER** steps. The worker parses this and attaches:

```
ROOT
 └── MASTER_PLANNER
       ├── RESEARCH_PROFILE          (PLUGIN)
       ├── FINANCIAL_PLANNER         (PLANNER, not expanded yet)
       └── RISK_PLANNER              (PLANNER, not expanded yet)
```

Example plan output from the master planner:

```json
[
  { "toolId": "RESEARCH_PROFILE", "input": { "prompt": "Company profile and overview for Tesla" } },
  {
    "toolId": "FINANCIAL_PLANNER",
    "type": "PLANNER",
    "params": {
      "modelPluginRef": "GPT4_EXECUTOR",
      "treeBuilder": "default",
      "userQueryVariable": "userQuery",
      "resultVariable": "__financial_planner_result"
    }
  },
  {
    "toolId": "RISK_PLANNER",
    "type": "PLANNER",
    "params": {
      "modelPluginRef": "GPT4_EXECUTOR",
      "treeBuilder": "default",
      "userQueryVariable": "userQuery",
      "resultVariable": "__risk_planner_result"
    }
  }
]
```

## Step 2 — Execute RESEARCH_PROFILE

Normal PLUGIN node. Runs the research profile tool. Tree structure unchanged.

## Step 3 — Execution Reaches FINANCIAL_PLANNER

When FINANCIAL_PLANNER is executed, it runs the LLM (with the same or a specialized prompt), parses the plan, and **dynamically attaches** its children:

```
ROOT
 └── MASTER_PLANNER
       ├── RESEARCH_PROFILE
       ├── FINANCIAL_PLANNER
       │      ├── REVENUE_ANALYSIS
       │      ├── PROFIT_ANALYSIS
       │      └── DEBT_ANALYSIS
       └── RISK_PLANNER
```

Sub-planner plan format is the same JSON array of steps (toolId + input); each step is a PLUGIN (e.g. tools registered as REVENUE_ANALYSIS, PROFIT_ANALYSIS, DEBT_ANALYSIS or a generic executor with different prompts).

## Step 4 — Execute Financial Children

Each of REVENUE_ANALYSIS, PROFIT_ANALYSIS, DEBT_ANALYSIS runs in order.

## Step 5 — Execution Reaches RISK_PLANNER

RISK_PLANNER runs and expands:

```
ROOT
 └── MASTER_PLANNER
       ├── RESEARCH_PROFILE
       ├── FINANCIAL_PLANNER
       │      ├── REVENUE_ANALYSIS
       │      ├── PROFIT_ANALYSIS
       │      └── DEBT_ANALYSIS
       └── RISK_PLANNER
              ├── MARKET_VOLATILITY
              ├── REGULATORY_RISK
              └── COMPETITOR_ANALYSIS
```

Then those three nodes execute.

## What This Demonstrates

| Concept | Description |
|--------|-------------|
| **Hierarchical planning** | Planner nodes can output steps whose `type` is `PLANNER`; the worker creates PLANNER children that expand when executed. |
| **Deferred expansion** | Subtrees exist only when execution reaches the planner node. |
| **Dynamic tree growth** | Tree shape is not fixed at workflow start. |
| **Deterministic-safe** | Each planner execution is a separate Temporal activity; retries use idempotent expansion (already-expanded planners do not attach children again). |

## Implementation Notes

- **Protocol:** `NodeSpec` supports `nodeType` (PLUGIN | PLANNER) and `params`. Planner steps use `NodeSpec.planner(displayName, logicalRef, params)`.
- **Parser:** `JsonStepsSubtreeBuilder` (default treeBuilder) parses steps with `"type": "PLANNER"` and optional `"params"` and produces PLANNER child specs.
- **Worker:** `DynamicNodeFactoryImpl` creates `NodeType.PLANNER` nodes with the given params when the spec is a planner step; otherwise creates PLUGIN nodes.
- **Limits:** Expansion limits (e.g. `maxExpansionDepth`, `maxPlannerInvocationsPerRun`) apply so nested planners are bounded.
- **Pipeline:** Use queue/pipeline `olo-recursive-research` and ensure scope includes the plugins used by the master and sub-planners (e.g. RESEARCH_PROFILE, GPT4_EXECUTOR). Register RESEARCH_PROFILE in the worker (or alias an existing model/tool plugin) so the master planner’s first step can run.

## Related Docs

- [Architecture and dynamic tree creation](architecture-and-features.md) (§3.8.1)
- [Pipeline configuration](pipeline-configuration-how-to.md)
- [Node type catalog](node-type-catalog.md) (PLANNER)
