# Run ledger database schema

When `OLO_RUN_LEDGER=true`, the worker persists run and node records to the database. Table names use the `olo_` prefix. Schema supports AI cost tracking (MODEL/PLANNER nodes), execution replay metadata, run-level aggregations, and production indexing.

**Bootstrap:** The worker runs the schema SQL at startup so tables and indexes are created if they do not exist. The script is `olo-run-ledger/src/main/resources/schema/olo-ledger.sql` (classpath resource `schema/olo-ledger.sql`). It is executed once by `JdbcLedgerStore.ensureSchema()` when the ledger is enabled.

## PostgreSQL (current schema)

The canonical schema is in **`olo-run-ledger/src/main/resources/schema/olo-ledger.sql`**. Summary:

- **olo_run** — One row per execution run. **run_id** (UUID PK), **tenant_id** (UUID NOT NULL), **tenant_name** (VARCHAR(255), semantic name e.g. "default"), pipeline, input_json (JSONB), **start_time** / **end_time** (TIMESTAMPTZ), status, total_nodes, total_cost, total_tokens, duration_ms, error_message, failure_stage, total_prompt_tokens, total_completion_tokens, currency. Config snapshot fields live in **olo_config** only.
- **olo_run_node** — One row per node **attempt**. **run_id** (UUID), **tenant_id** (UUID, nullable), **tenant_name** (VARCHAR(255)), **node_id** (UUID), **node_name** (VARCHAR(255), semantic name e.g. "root", "plannerNode"), node_type, input_snapshot/output_snapshot (JSONB), start_time/end_time (TIMESTAMPTZ), status, error_code, error_message, error_details (JSONB), token/cost columns, model_name, provider, replay columns, retry columns, **parent_node_id** (UUID), **parent_node_name** (VARCHAR(255)), execution_order, depth. FK run_id → olo_run ON DELETE CASCADE.
- **olo_config** — Immutable config snapshot per run. **run_id** (UUID PK, FK → olo_run), **tenant_id** (UUID NOT NULL), **tenant_name** (VARCHAR(255)), pipeline, config_version, snapshot_version_id, plugin_versions, **config_tree_json** (JSONB, serialized pipeline definition / execution tree), **tenant_config_json** (JSONB, serialized tenant config map), created_at (TIMESTAMPTZ). Written once at run start by `JdbcLedgerStore.configRecorded()` (called from `runStarted()`).

**Id vs name:** All **id** columns (run_id, tenant_id, node_id, parent_node_id) are **always UUID**. **Name** columns (tenant_name, node_name, parent_node_name) store the semantic/display identifier (e.g. "default", "root", "plannerNode") when the app passes a non-UUID; use name for display and querying by human-readable id.

**Indexes:** idx_olo_run_tenant_start, idx_olo_run_tenant_pipeline_start, idx_olo_run_status, idx_olo_run_pipeline; idx_olo_run_node_run, idx_olo_run_node_tenant, idx_olo_run_node_status, idx_olo_run_node_type, idx_olo_run_node_parent; idx_olo_config_tenant_pipeline.

**Java:** `JdbcLedgerStore` stores **id** columns as UUID only: `toUuid(s)` parses a valid UUID or produces a deterministic UUID from a semantic string. The same raw string is stored in the corresponding **name** column via `toName(s, 255)` so both id (UUID) and name (display) are persisted.

**Troubleshooting — tables empty:** Ensure `OLO_RUN_LEDGER=true` (default in dev), and that DB is reachable: `OLO_DB_HOST`, `OLO_DB_PORT`, `OLO_DB_NAME`, `OLO_DB_USER`, `OLO_DB_PASSWORD`. Schema is created at bootstrap from `schema/olo-ledger.sql`. If JDBC init fails, the worker falls back to a no-op store and no rows are written. If you see **"Ledger runStarted failed"** or **"Ledger nodeStarted failed"** in logs with an error like **"column tenant_name does not exist"**, the tables were created before the name columns were added — run the **ALTER** statements in the "Migrations from previous schema" section above (e.g. `ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS tenant_name VARCHAR(255);` etc.).

## Migrations from previous schema

If you already have `olo_run` / `olo_run_node` with VARCHAR or missing columns/tables:

```sql
-- Add olo_config if missing (config snapshot per run)
CREATE TABLE IF NOT EXISTS olo_config (
    run_id                  UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    tenant_name             VARCHAR(255),
    pipeline                VARCHAR(255) NOT NULL,
    config_version          VARCHAR(64),
    snapshot_version_id     VARCHAR(64),
    plugin_versions         TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_olo_config_tenant_pipeline ON olo_config(tenant_id, pipeline);

-- Add new columns to olo_run (if not present)
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS tenant_name VARCHAR(255);
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS failure_stage VARCHAR(128);
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_prompt_tokens INT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_completion_tokens INT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS currency VARCHAR(8) DEFAULT 'USD';

-- Add new columns to olo_run_node (if not present)
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS tenant_name VARCHAR(255);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS node_name VARCHAR(255);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS parent_node_name VARCHAR(255);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS error_details JSONB;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS prompt_cost DECIMAL(10,6);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS completion_cost DECIMAL(10,6);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS total_cost DECIMAL(10,6);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS temperature DECIMAL(5,4);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS top_p DECIMAL(5,4);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(255);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS attempt INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS max_attempts INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS backoff_ms BIGINT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS execution_stage VARCHAR(64);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS failure_type VARCHAR(128);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS parent_node_id UUID;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS execution_order INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS depth INT;
CREATE INDEX IF NOT EXISTS idx_olo_run_node_parent ON olo_run_node(parent_node_id);

-- Add olo_config columns for serialized config tree and tenant config (audit)
ALTER TABLE olo_config ADD COLUMN IF NOT EXISTS config_tree_json JSONB;
ALTER TABLE olo_config ADD COLUMN IF NOT EXISTS tenant_config_json JSONB;
```

**Migrating existing VARCHAR IDs to UUID** (only if current columns are varchar/text):

```sql
-- Drop FKs that reference run_id before altering
ALTER TABLE olo_run_node DROP CONSTRAINT IF EXISTS olo_run_node_run_id_fkey;
ALTER TABLE olo_config DROP CONSTRAINT IF EXISTS olo_config_run_id_fkey;
-- Convert columns
ALTER TABLE olo_run ALTER COLUMN run_id TYPE UUID USING run_id::uuid;
ALTER TABLE olo_run ALTER COLUMN tenant_id TYPE UUID USING tenant_id::uuid;
ALTER TABLE olo_run_node ALTER COLUMN run_id TYPE UUID USING run_id::uuid;
ALTER TABLE olo_run_node ALTER COLUMN tenant_id TYPE UUID USING NULLIF(trim(tenant_id), '')::uuid;
ALTER TABLE olo_run_node ALTER COLUMN node_id TYPE UUID USING node_id::uuid;
ALTER TABLE olo_run_node ALTER COLUMN parent_node_id TYPE UUID USING NULLIF(trim(parent_node_id), '')::uuid;
ALTER TABLE olo_config ALTER COLUMN run_id TYPE UUID USING run_id::uuid;
ALTER TABLE olo_config ALTER COLUMN tenant_id TYPE UUID USING tenant_id::uuid;
-- Re-add FKs
ALTER TABLE olo_run_node ADD CONSTRAINT olo_run_node_run_id_fkey FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE;
ALTER TABLE olo_config ADD CONSTRAINT olo_config_run_id_fkey FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE;
```

**Migrating timestamps to TIMESTAMPTZ** (if currently without time zone):

```sql
ALTER TABLE olo_run ALTER COLUMN start_time TYPE TIMESTAMPTZ USING start_time AT TIME ZONE 'UTC';
ALTER TABLE olo_run ALTER COLUMN end_time TYPE TIMESTAMPTZ USING end_time AT TIME ZONE 'UTC';
ALTER TABLE olo_run_node ALTER COLUMN start_time TYPE TIMESTAMPTZ USING start_time AT TIME ZONE 'UTC';
ALTER TABLE olo_run_node ALTER COLUMN end_time TYPE TIMESTAMPTZ USING end_time AT TIME ZONE 'UTC';
ALTER TABLE olo_config ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
```

## Partitioning (future-proofing)

For high volume, partition by time to avoid table bloat and vacuum pressure:

```sql
-- Example: monthly range partitioning (PostgreSQL 10+)
-- CREATE TABLE olo_run (...) PARTITION BY RANGE (start_time);
-- CREATE TABLE olo_run_2026_02 PARTITION OF olo_run FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
```

## Environment

- `OLO_RUN_LEDGER` — set to `true` to enable run ledger.
- `OLO_DB_HOST`, `OLO_DB_PORT`, `OLO_DB_NAME`, `OLO_DB_USER`, `OLO_DB_PASSWORD` — ledger DB connection.

## Audit persistence of plugin version

**olo_config.plugin_versions** is the **audit record** of which plugin versions were in use for the run. It is written once at run start when the ledger records the config snapshot and is **immutable** for the life of the run.

- **Format**: JSON object (TEXT). Keys = plugin ids (every `pluginRef` used in the execution tree for that run); values = contract version from the registry (e.g. `"1.0"`) or `"?"` if unknown. Example: `{"GPT4_EXECUTOR":"1.0","QDRANT_VECTOR_STORE":"1.0"}`.
- **Source**: Built at run start by collecting all plugin refs from the pipeline’s execution tree and resolving each via `PluginRegistry.getContractVersion(tenantId, pluginId)`. Stored in **olo_config** only (not on olo_run or olo_run_node).
- **Use**: Audit and compliance (which plugin versions ran), debugging, and compatibility analysis. Query by run_id via olo_config to see the exact plugin versions for that run.

## Design

- **AI cost**: MODEL/PLANNER nodes get token_input_count, token_output_count, estimated_cost, prompt_cost, completion_cost, total_cost, model_name, provider from plugin output.
- **Run aggregations**: total_nodes, total_cost, total_tokens, duration_ms, error_message, failure_stage, total_prompt_tokens, total_completion_tokens, currency set on run end.
- **Config snapshot**: Stored only in olo_config (run_id, tenant_id, pipeline, config_version, snapshot_version_id, plugin_versions); no duplication on olo_run.
- **Replay**: prompt_hash, model_config_json, tool_calls_json, external_payload_ref, temperature, top_p, provider_request_id support deterministic replay.
- **Failure intelligence**: error_code, error_message, error_details, retry_count, attempt, max_attempts, backoff_ms, execution_stage, failure_type for reliability analytics.
- **Hierarchy**: parent_node_id, parent_node_name, execution_order, depth for tree reconstruction.
- **Id vs name**: id columns (run_id, tenant_id, node_id, parent_node_id) are always UUID; name columns (tenant_name, node_name, parent_node_name) store the semantic/display value for querying and UI.

## Ledger is append-only; each attempt recorded separately

The run ledger is **write-only** and **append-only**. It does not update previous rows to reflect retries.

- **Per node attempt:** When a node is retried (e.g. by a RETRY parent), **each attempt is recorded separately**. One row per `(run_id, node_id, attempt)`.
- **Uniqueness:** Uniqueness is enforced by **run_id + node_id + attempt**. The schema today uses PK `(run_id, node_id)`; to support multiple attempts per node, the primary key is extended to include **attempt** (e.g. `(run_id, node_id, attempt)`), so each attempt is a distinct row. No in-place update of a previous attempt’s row.
- **Implications:** NodeLedgerFeature (and the store) write one **INSERT** at node start and one **UPDATE** for that same row at node end, keyed by `(run_id, node_id, attempt)`. When the runtime supports retries, it passes the current attempt number so the ledger can insert a new row per attempt and update only that row on end. Until **attempt** is part of the key and the runtime passes it, nodes that are retried may only persist the last attempt’s outcome in the current schema.

---

## Run ledger implementation details

This section documents how the worker creates and reuses **run_id**, when **runStarted** / **runEnded** and node records are written, and how the **per-node (linear/dynamic)** execution path shares a single run across multiple activity invocations.

### Two execution paths

The workflow can run in two ways:

1. **Single-activity path (runExecutionTree)**  
   When the execution tree is non-linear (e.g. IF, SWITCH, FORK) or the plan is not linear, the workflow calls **one** activity: `runExecutionTree(queueName, workflowInputJson)`. That activity runs the full tree in one JVM call via `ExecutionEngine.run(...)`. One runId is created at the start of the activity; all nodes run on the same thread (or same runId is passed into async branches).

2. **Per-node path (executeNode)**  
   When the plan is linear (only SEQUENCE, GROUP, and leaf nodes), the workflow calls **one activity per node**: `executeNode(activityType, planJson, nodeId, variableMapJson, queueName, workflowInputJson, dynamicStepsJson)`. Each invocation is a separate Temporal activity; without a shared runId, each would create a new run row. The implementation **reuses a single runId** for the whole logical run (see below).

### Where runId is created and LedgerContext set

- **LedgerContext** (`olo-run-ledger`) is a **thread-local** holder for the current run id. **NodeLedgerFeature** (and any code that needs the current run) reads `LedgerContext.getRunId()`. If it is null, the feature skips persisting (and logs a warning). So runId must be set on the **same thread** that runs the node.

- **runExecutionTree path**  
  - At the start of the activity: a new **runId** is generated (`UUID.randomUUID().toString()`). If the bootstrap did not provide a RunLedger, an **effectiveRunLedger** is used: `runLedger != null ? runLedger : new RunLedger(new NoOpLedgerStore())`, so runId is always set.  
  - The activity sets `LedgerContext.setRunId(runId)`, builds an **ExecutionConfigSnapshot** with that runId, and calls `ExecutionEngine.run(snapshot, ...)`.  
  - **ExecutionEngine** creates a **NodeExecutor** with `ledgerRunId = snapshot.getRunId()`. **NodeExecutor** sets `LedgerContext.setRunId(ledgerRunId)` at the start of each node execution (and in async branches) so node features always see a non-null runId.  
  - In a `finally` block the activity calls `effectiveRunLedger.runEnded(...)` and `LedgerContext.clear()`.

- **executeNode path (per-node)**  
  - The **execution plan** (from `getExecutionPlan`) now includes a **runId** field: when the plan is built (linear or parallel steps), the activity adds `out.put("runId", UUID.randomUUID().toString())`. The same plan JSON is passed to **every** `executeNode` call for that workflow run.  
  - In **executeNode(payloadJson)**: the activity reads **runId from the plan** (`plan.get("runId")`). If present and non-blank, that value is **reused** as the runId for this invocation; otherwise a new UUID is generated (backward compatibility). So all nodes in a single logical run (PLANNER, then PLUGIN, then dynamic steps) share the **same runId**.  
  - The activity sets `LedgerContext.setRunId(runId)` and passes **runId** into **NodeExecutor** as `ledgerRunId`, so the executor also sets LedgerContext at the start of each node (and clears in finally).  
  - **runStarted** is called **only on the first node** in the plan: `isFirstNodeInPlan(plan, nodeId)` checks whether the current node is the first in the plan's `nodes` or `steps` list. Only then does the activity call `effectiveRunLedger.runStarted(runId, ...)`. So only one **olo_run** row is created per logical run.  
  - **runEnded** is called in the activity's `finally` block **for every** executeNode invocation. Each call updates the same **olo_run** row (same run_id); the last node's update wins (final status, end_time, etc.). This works even when the "last" node is a dynamic step not present in the plan's nodes list.  
  - If the bootstrap did not provide a RunLedger, **effectiveRunLedger** is again a no-op: `new RunLedger(new NoOpLedgerStore())`, so runId is still set and node features do not skip.

### Single runId for per-node (linear/dynamic) runs — summary

| Step | Where | What happens |
|------|--------|----------------|
| 1 | **getExecutionPlan** | Plan JSON is built with `runId: "<uuid>"` (one per plan). |
| 2 | **Workflow** | Passes the same `planJson` (including runId) to every `executeNode(..., planJson, ...)`. |
| 3 | **executeNode** | Reads `runId` from plan; reuses it; sets LedgerContext and passes it to NodeExecutor. |
| 4 | **First node only** | `isFirstNodeInPlan(plan, nodeId)` is true → call `runStarted(runId, ...)` (one olo_run INSERT). |
| 5 | **Every node** | NodeLedgerFeature sees non-null LedgerContext → INSERT/UPDATE olo_run_node for that node. |
| 6 | **Every node (finally)** | Call `runEnded(runId, ...)` so olo_run row is updated; last invocation sets final state. |

Result: one **olo_run** row per logical run and one **olo_run_node** row per node, all with the same **run_id**, for both static linear nodes and planner-injected dynamic steps.

### JdbcLedgerStore: nodeStarted INSERT and JSONB

- **Parameter count:** The PostgreSQL JDBC driver can treat `?::jsonb` in SQL as **two** placeholders, which leads to "No value specified for parameter 14/15". To avoid that, the **input_snapshot** column is bound using a **plain `?`** and a **PGobject** with type `"jsonb"` (`org.postgresql.util.PGobject`). Helper: `toJsonbPgObject(String json)` creates the object; `ps.setObject(7, toJsonbPgObject(...))` so the driver sees exactly one placeholder per column.  
- **attempt column:** The **olo_run_node** INSERT includes an **attempt** column (value `1` at node start). This satisfies binding and supports future per-attempt rows when the schema and runtime use attempt in the key.

### Bootstrap and fallbacks

- **OLO_RUN_LEDGER:** Default is **true** when unset (`OloConfig`). Set to `false` to disable the ledger.  
- If the bootstrap **does not** provide a RunLedger (e.g. JDBC init failed or ledger disabled), **OloWorkerApplication** can use a fallback `RunLedger(new NoOpLedgerStore())` so the activity always has a non-null RunLedger.  
- In the activity (**runExecutionTree** and **executeNode**), if the injected RunLedger is null, **effectiveRunLedger** is set to `new RunLedger(new NoOpLedgerStore())` so LedgerContext and runId are still set and node features do not skip; no DB rows are written when using the no-op store.
