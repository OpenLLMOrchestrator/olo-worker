# OLO Diagrams

This folder is the canonical place for **architecture diagram assets** and their descriptions. Diagrams make the system instantly understandable.

| Diagram | Explains | ASCII / detail in |
|---------|----------|-------------------|
| **runtime-stack.png** | What OLO is (ecosystem: Apps → Agent Frameworks → OLO Runtime → Temporal → Infrastructure) | [overview/runtime-stack-diagram.md](../overview/runtime-stack-diagram.md), root [README](../../README.md#what-is-olo) |
| **execution-flow.png** | How a pipeline run flows (User → Request → Temporal → Execution Engine → Tree → Node Executor → Plugin → External Service) | [arcitecture/execution-tree-design.md](../arcitecture/execution-tree-design.md) §Execution flow diagram |
| **plugin-system.png** | Plugin architecture (Engine → PluginRegistry → RuntimePlugin → createRuntime → PluginRuntime → Capabilities; and JAR → Manifest → Loader → Classloader → Registry) | [arcitecture/plugin-design.md](../arcitecture/plugin-design.md), [plugin-discovery-and-loading.md](../arcitecture/plugin-discovery-and-loading.md) |
| **connection-secret-flow.png** | Connection + secret resolution (Pipeline → ConnectionRuntimeManager → ConnectionResolver → ConfigInterpolator → SecretResolver → SecretRegistry → SecretProvider → Vault; then Resolved Config → createRuntime → Cached PluginRuntime) | [arcitecture/connection-manager-design.md](../arcitecture/connection-manager-design.md), [secret-architecture.md](../arcitecture/secret-architecture.md) |
| **event-flow.png** | Real-time event flow (Execution Engine → Execution Events → Event Bus → UI Stream, Run Ledger, Metrics, Logs, Debug Timeline) | [arcitecture/event-communication-architecture.md](../arcitecture/event-communication-architecture.md) §Real-time event flow |

---

## What each diagram achieves

| Diagram | Purpose |
|---------|---------|
| **Runtime Stack** | Clarifies that OLO is **AI runtime infrastructure**, not an agent library. |
| **Execution Flow** | Shows the core runtime path when a pipeline runs. |
| **Plugin System** | Visualizes extensibility (registry, runtime, capabilities, discovery). |
| **Connection + Secrets** | Shows how runtime configuration and secrets are resolved before createRuntime. |
| **Event Flow** | Shows how events power chat UI, execution timeline, step logs, and live progress. |

---

## Creating PNGs

The ASCII versions of these diagrams live in the linked docs. To add PNGs:

1. **runtime-stack.png** — From [overview/runtime-stack-diagram.md](../overview/runtime-stack-diagram.md) or root README ecosystem diagram.
2. **execution-flow.png** — From execution-tree-design.md §Execution flow diagram.
3. **plugin-system.png** — From plugin-design.md and plugin-discovery-and-loading.md plugin flow sections.
4. **connection-secret-flow.png** — From connection-manager-design.md and secret-architecture.md resolution flow.
5. **event-flow.png** — From event-communication-architecture.md §Real-time event flow.

Use any tool (draw.io, Mermaid export, Excalidraw, etc.) to render the flows; keep this README and the doc sections in sync.
