# Run ledger database schema

When `OLO_RUN_LEDGER=true`, the worker persists run and node records to the database. Table names use the `olo_` prefix. Schema supports AI cost tracking (MODEL/PLANNER nodes), execution replay metadata, run-level aggregations, and production indexing.

**Bootstrap:** The worker runs the schema SQL at startup so tables and indexes are created if they do not exist. The script is `olo-run-ledger/src/main/resources/schema/olo-ledger.sql` (classpath resource `schema/olo-ledger.sql`). It is executed once by `JdbcLedgerStore.ensureSchema()` when the ledger is enabled.

## PostgreSQL (full schema)

```sql
-- Run-level record: one row per execution run.
-- Config snapshot (config_version, snapshot_version_id, plugin_versions) is in olo_config only; no duplication.
CREATE TABLE IF NOT EXISTS olo_run (
    run_id                  UUID PRIMARY KEY,
    tenant_id               VARCHAR(255) NOT NULL,
    pipeline                VARCHAR(255) NOT NULL,
    pipeline_checksum       VARCHAR(128),
    execution_engine_version VARCHAR(64),
    input_json              JSONB,
    start_time              TIMESTAMPTZ NOT NULL,
    end_time                TIMESTAMPTZ,
    final_output            TEXT,
    status                  VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    total_nodes             INT,
    total_cost              DECIMAL(10,6),
    total_tokens            INT,
    duration_ms             BIGINT
);

-- Node-level record: one row per node execution. tenant_id denormalized for ultra-fast analytics without join.
CREATE TABLE IF NOT EXISTS olo_run_node (
    run_id                  UUID NOT NULL,
    tenant_id               VARCHAR(255) NOT NULL,
    node_id                 VARCHAR(255) NOT NULL,
    node_type               VARCHAR(64) NOT NULL,
    input_snapshot          JSONB,
    start_time              TIMESTAMPTZ NOT NULL,
    output_snapshot         JSONB,
    end_time                TIMESTAMPTZ,
    status                  VARCHAR(32) NOT NULL,
    error_message           TEXT,
    token_input_count       INT,
    token_output_count      INT,
    estimated_cost          DECIMAL(10,6),
    model_name              VARCHAR(128),
    provider                VARCHAR(64),
    prompt_hash             VARCHAR(128),
    model_config_json       JSONB,
    tool_calls_json         JSONB,
    external_payload_ref    VARCHAR(512),
    retry_count             INT,
    execution_stage         VARCHAR(64),
    failure_type            VARCHAR(128),
    PRIMARY KEY (run_id, node_id),
    FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE
);

-- Indexes for production: dashboards, filters, running workflows, failed nodes, AI steps.
CREATE INDEX IF NOT EXISTS idx_olo_run_tenant_start ON olo_run(tenant_id, start_time);
CREATE INDEX IF NOT EXISTS idx_olo_run_tenant_pipeline_start ON olo_run(tenant_id, pipeline, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_olo_run_status ON olo_run(status);
CREATE INDEX IF NOT EXISTS idx_olo_run_pipeline ON olo_run(pipeline);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_run ON olo_run_node(run_id);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_tenant ON olo_run_node(tenant_id);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_status ON olo_run_node(status);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_type ON olo_run_node(node_type);

-- Optional: index JSON path for multi-tenant analytics (e.g. filter by userId in input).
-- CREATE INDEX idx_olo_run_input_user ON olo_run ((input_json->>'userId'));
```

## Migrations from previous schema

If you already have `olo_run` / `olo_run_node` with TEXT columns, add new columns and alter types:

```sql
-- Add new columns to olo_run
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS pipeline_checksum VARCHAR(128);
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS execution_engine_version VARCHAR(64);
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_nodes INT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_cost DECIMAL(10,6);
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_tokens INT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
-- Optionally migrate to JSONB: ALTER TABLE olo_run ALTER COLUMN input_json TYPE JSONB USING input_json::jsonb;

-- Add new columns to olo_run_node
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS token_input_count INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS token_output_count INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS estimated_cost DECIMAL(10,6);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS model_name VARCHAR(128);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS provider VARCHAR(64);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(128);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS model_config_json JSONB;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS tool_calls_json JSONB;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS external_payload_ref VARCHAR(512);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS retry_count INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS execution_stage VARCHAR(64);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS failure_type VARCHAR(128);

-- Platform-ready: run-level error and token/cost breakdown
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS failure_stage VARCHAR(128);
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_prompt_tokens INT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS total_completion_tokens INT;
ALTER TABLE olo_run ADD COLUMN IF NOT EXISTS currency VARCHAR(8) DEFAULT 'USD';

-- Platform-ready: node-level error details, cost breakdown, replay, retry, hierarchy
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
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS parent_node_id VARCHAR(255);
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS execution_order INT;
ALTER TABLE olo_run_node ADD COLUMN IF NOT EXISTS depth INT;
CREATE INDEX IF NOT EXISTS idx_olo_run_node_parent ON olo_run_node(parent_node_id);

-- Migrate run_id from VARCHAR(255) to UUID (smaller storage, faster comparisons). Only if current type is varchar.
-- ALTER TABLE olo_run_node DROP CONSTRAINT IF EXISTS olo_run_node_run_id_fkey;
-- ALTER TABLE olo_config DROP CONSTRAINT IF EXISTS olo_config_run_id_fkey;
-- ALTER TABLE olo_run ALTER COLUMN run_id TYPE UUID USING run_id::uuid;
-- ALTER TABLE olo_run_node ALTER COLUMN run_id TYPE UUID USING run_id::uuid;
-- ALTER TABLE olo_config ALTER COLUMN run_id TYPE UUID USING run_id::uuid;
-- ALTER TABLE olo_run_node ADD CONSTRAINT olo_run_node_run_id_fkey FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE;
-- ALTER TABLE olo_config ADD CONSTRAINT olo_config_run_id_fkey FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE;

-- Migrate timestamp → timestamptz (UTC, safe for distributed workers and cross-region analytics).
-- ALTER TABLE olo_run ALTER COLUMN start_time TYPE TIMESTAMPTZ USING start_time AT TIME ZONE 'UTC';
-- ALTER TABLE olo_run ALTER COLUMN end_time TYPE TIMESTAMPTZ USING end_time AT TIME ZONE 'UTC';
-- ALTER TABLE olo_run_node ALTER COLUMN start_time TYPE TIMESTAMPTZ USING start_time AT TIME ZONE 'UTC';
-- ALTER TABLE olo_run_node ALTER COLUMN end_time TYPE TIMESTAMPTZ USING end_time AT TIME ZONE 'UTC';
-- ALTER TABLE olo_config ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';

-- Tenant + pipeline + time range queries.
-- CREATE INDEX IF NOT EXISTS idx_olo_run_tenant_pipeline_start ON olo_run(tenant_id, pipeline, start_time DESC);
```

## Partitioning (future-proofing)

For high volume, partition by time to avoid table bloat and vacuum pressure:

```sql
-- Example: monthly range partitioning (PostgreSQL 10+)
-- CREATE TABLE olo_run (...) PARTITION BY RANGE (start_time);
-- CREATE TABLE olo_run_2026_02 PARTITION OF olo_run FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- CREATE TABLE olo_run_2026_03 PARTITION OF olo_run FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
```

## Environment

- `OLO_RUN_LEDGER` — set to `true` to enable run ledger.
- `OLO_DB_HOST`, `OLO_DB_PORT`, `OLO_DB_NAME`, `OLO_DB_USER`, `OLO_DB_PASSWORD` — ledger DB connection.

## Design

- **AI cost**: MODEL/PLANNER nodes get `token_input_count`, `token_output_count`, `estimated_cost`, `model_name`, `provider` from plugin output (e.g. Ollama response).
- **Run aggregations**: `total_nodes`, `total_cost`, `total_tokens`, `duration_ms` set on run end (computed from node rows / activity).
- **Replay**: `prompt_hash`, `model_config_json`, `tool_calls_json`, `external_payload_ref` support deterministic replay; large payloads in object storage.
- **Failure intelligence**: `retry_count`, `execution_stage`, `failure_type` for reliability analytics.
- **Snapshot versioning**: `pipeline_checksum`, `execution_engine_version` for compliance-grade audit.
