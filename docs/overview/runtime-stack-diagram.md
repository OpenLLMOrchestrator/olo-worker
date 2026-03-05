# OLO Runtime Stack — Where OLO Fits

This document explains **what OLO is** in the ecosystem: AI runtime infrastructure below agent frameworks and above Temporal and infrastructure. See also the [What is OLO?](../../README.md#what-is-olo) section in the root README.

---

## The Olo Runtime Stack (10-Second Architecture Diagram)

At a glance, the stack looks like this:

```
                    ┌──────────────────────────────┐
                    │            OLO UI            │
                    │  Chat • Pipeline Builder     │
                    │  Execution Timeline • Logs   │
                    └──────────────┬───────────────┘
                                   │
                         Execution Events / Streams
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │        OLO API / SDK         │
                    │  ctx.model() ctx.vector()    │
                    │  ctx.tool() ctx.prompt()     │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │        Pipeline Engine        │
                    │  Execution Tree / Planner     │
                    │  Steps • Variables • State    │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │     Resource Runtime Layer    │
                    │  ResourceRuntimeManager       │
                    │  ResourceResolver             │
                    │  SecretResolver               │
                    │  Runtime Cache                │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │        Plugin System          │
                    │  Model Plugins                │
                    │  Vector DB Plugins            │
                    │  Tool Plugins                 │
                    │  Storage Plugins              │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │      External Systems         │
                    │  OpenAI • Ollama • pgvector   │
                    │  Redis • S3 • Databases       │
                    └──────────────────────────────┘
```

---

## How a Contributor Reads This (10 seconds)

A new contributor can follow the stack top to bottom:

| Step | Layer | What it is |
|------|--------|------------|
| **1** | **UI** | Chat, pipeline visualization, execution timeline, logs. |
| **2** | **API / SDK** | Pipelines interact through `ctx.model("openai-prod")`, `ctx.vector("pgvector-main")`, `ctx.tool("web-search")`, `ctx.prompt(...)`. |
| **3** | **Pipeline engine** | Runs the execution tree: nodes, variables, planner, control flow. |
| **4** | **Resource runtime layer** | Resolves tenant + resource name; creates or fetches cached runtime clients (ResourceRuntimeManager, ResourceResolver, SecretResolver). |
| **5** | **Plugin system** | Plugins create real clients: OpenAI, pgvector, Redis, etc. |
| **6** | **External systems** | Actual infrastructure (APIs, DBs, storage). |

**Flow:** UI → Execution events/streams → API/SDK → Pipeline engine → Resource runtime layer → Plugin system → External systems.

---

## OLO in the ecosystem

OLO is **not** an agent framework. It is the **runtime layer** that agent frameworks and applications can build on:

```
Applications
(Chat, Agents, AI Apps)
        │
        ▼
Agent Frameworks
(LangChain, CrewAI, etc.)
        │
        ▼
OLO Runtime
 ├ Execution Kernel
 ├ Feature System
 ├ Plugin System
 ├ Connection Runtime
 ├ Secret System
 └ Event System
        │
        ▼
Temporal Workflow Engine
        │
        ▼
Infrastructure
(DB, Redis, Vault, APIs)
```

| Layer | Role |
|-------|------|
| **Applications** | Chat UIs, agent apps, AI products that use pipelines. |
| **Agent frameworks** | LangChain, CrewAI, etc. — can run on top of OLO or alongside it. |
| **OLO Runtime** | Execution Kernel (declarative Execution Tree), Feature System (logging, quota, metrics, ledger), Plugin System (LLM, tools, DB), Connection Runtime, Secret System, Event System. |
| **Temporal** | Workflow orchestration: retries, timeouts, durability, activities. |
| **Infrastructure** | Databases, Redis, Vault, external APIs. |

**Takeaway:** OLO is **AI runtime infrastructure**. It gives you pipelines, plugins, connections, secrets, and events—orchestrated by Temporal—so you can build or integrate agent frameworks and apps without reimplementing the runtime.

---

## Full runtime stack (in-repo diagram)

The **10-second diagram** above is the quick view. For the detailed in-process stack (User/API → Workflow → Execution Engine → Execution Tree → Features/Variables → Plugins → Tenant infra → Run Ledger), see:

- Root [README — Olo Runtime Architecture](../../README.md#olo-runtime-architecture)
- [architecture-and-features](../arcitecture/architecture-and-features.md) — §Olo Runtime Stack
- [connection-manager-design](../arcitecture/connection-manager-design.md) — Resource runtime layer (ResourceRuntimeManager, ResourceResolver, SecretResolver)

For diagram assets and ASCII sources, see [diagrams/](../diagrams/README.md).
