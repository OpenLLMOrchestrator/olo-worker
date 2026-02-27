-- Run ledger schema: executed at bootstrap when OLO_RUN_LEDGER=true.
-- Creates olo_run and olo_run_node if they do not exist.
-- For existing DBs, run the ALTERs in docs/run-ledger-schema.md (migration section).

-- Run-level record: one row per execution run.
-- id columns (run_id, tenant_id, node_id) are always UUID. name columns store semantic display names (e.g. "default", "root").
-- olo_run: execution run only. Config snapshot lives in olo_config.
CREATE TABLE IF NOT EXISTS olo_run (
    run_id                      UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL,
    tenant_name                 VARCHAR(255),
    pipeline                    VARCHAR(255) NOT NULL,
    pipeline_checksum           VARCHAR(128),
    execution_engine_version    VARCHAR(64),
    input_json                  JSONB,
    start_time                  TIMESTAMPTZ NOT NULL,
    end_time                    TIMESTAMPTZ,
    final_output                TEXT,
    status                      VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    total_nodes                 INT,
    total_cost                  DECIMAL(10,6),
    total_tokens                INT,
    duration_ms                 BIGINT,
    -- Error and failure (why did the run fail?)
    error_message               TEXT,
    failure_stage               VARCHAR(128),
    -- Token and cost breakdown (run-level aggregates)
    total_prompt_tokens         INT,
    total_completion_tokens     INT,
    currency                    VARCHAR(8) DEFAULT 'USD'
);

-- Node-level record: one row per node execution. tenant_id denormalized for ultra-fast analytics without join (nullable for backward-compat when not provided).
CREATE TABLE IF NOT EXISTS olo_run_node (
    run_id                      UUID NOT NULL,
    tenant_id                   UUID,
    tenant_name                 VARCHAR(255),
    node_id                     UUID NOT NULL,
    node_name                   VARCHAR(255),
    node_type                   VARCHAR(64) NOT NULL,
    input_snapshot              JSONB,
    start_time                  TIMESTAMPTZ NOT NULL,
    output_snapshot             JSONB,
    end_time                    TIMESTAMPTZ,
    status                      VARCHAR(32) NOT NULL,
    -- Error details (why did this node fail?)
    error_code                  VARCHAR(64),
    error_message               TEXT,
    error_details               JSONB,
    -- Token and cost (per node / per provider)
    token_input_count           INT,
    token_output_count          INT,
    estimated_cost              DECIMAL(10,6),
    prompt_cost                 DECIMAL(10,6),
    completion_cost             DECIMAL(10,6),
    total_cost                  DECIMAL(10,6),
    model_name                  VARCHAR(128),
    provider                    VARCHAR(64),
    -- Replay / determinism (temperature, top_p, seed, raw request/response, provider id)
    prompt_hash                 VARCHAR(128),
    model_config_json           JSONB,
    tool_calls_json             JSONB,
    external_payload_ref        VARCHAR(512),
    temperature                 DECIMAL(5,4),
    top_p                       DECIMAL(5,4),
    provider_request_id         VARCHAR(255),
    -- Retry / attempt tracking
    retry_count                 INT,
    attempt                     INT,
    max_attempts                INT,
    backoff_ms                  BIGINT,
    execution_stage             VARCHAR(64),
    failure_type                VARCHAR(128),
    -- Execution hierarchy (tree reconstruction, parallel/conditional/loop)
    parent_node_id              UUID,
    parent_node_name            VARCHAR(255),
    execution_order             INT,
    depth                       INT,
    PRIMARY KEY (run_id, node_id),
    FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_olo_run_tenant_start ON olo_run(tenant_id, start_time);
CREATE INDEX IF NOT EXISTS idx_olo_run_tenant_pipeline_start ON olo_run(tenant_id, pipeline, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_olo_run_status ON olo_run(status);
CREATE INDEX IF NOT EXISTS idx_olo_run_pipeline ON olo_run(pipeline);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_run ON olo_run_node(run_id);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_tenant ON olo_run_node(tenant_id);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_status ON olo_run_node(status);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_type ON olo_run_node(node_type);
CREATE INDEX IF NOT EXISTS idx_olo_run_node_parent ON olo_run_node(parent_node_id);

-- Config snapshot per run: which config was used for each run (for audit/analytics).
CREATE TABLE IF NOT EXISTS olo_config (
    run_id                  UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    tenant_name             VARCHAR(255),
    pipeline                VARCHAR(255) NOT NULL,
    config_version          VARCHAR(64),
    snapshot_version_id     VARCHAR(64),
    plugin_versions         TEXT,
    config_tree_json        JSONB,
    tenant_config_json      JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id) REFERENCES olo_run(run_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_olo_config_tenant_pipeline ON olo_config(tenant_id, pipeline);
