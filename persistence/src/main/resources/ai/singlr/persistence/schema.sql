CREATE TABLE IF NOT EXISTS helios_prompts (
    id          UUID            PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    content     TEXT            NOT NULL,
    version     INT             NOT NULL,
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    variables   TEXT[]          NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL,
    UNIQUE (name, version)
);

CREATE INDEX IF NOT EXISTS idx_helios_prompts_name
    ON helios_prompts (name);

CREATE UNIQUE INDEX IF NOT EXISTS idx_helios_prompts_single_active
    ON helios_prompts (name) WHERE active;

CREATE TABLE IF NOT EXISTS helios_traces (
    id                UUID            PRIMARY KEY,
    name              VARCHAR(255)    NOT NULL,
    start_time        TIMESTAMPTZ     NOT NULL,
    end_time          TIMESTAMPTZ,
    error             TEXT,
    attributes        JSONB           NOT NULL DEFAULT '{}',
    input_text        TEXT,
    output_text       TEXT,
    user_id           VARCHAR(255),
    session_id        UUID,
    model_id          VARCHAR(255),
    prompt_name       VARCHAR(255),
    prompt_version    INT,
    total_tokens      INT             NOT NULL DEFAULT 0,
    input_tokens      INT,
    output_tokens     INT,
    cache_creation_tokens INT,
    cache_read_tokens INT,
    cost_micro_usd    BIGINT,
    thumbs_up_count   INT             NOT NULL DEFAULT 0,
    thumbs_down_count INT             NOT NULL DEFAULT 0,
    group_id          VARCHAR(255),
    labels            JSONB           NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS idx_helios_traces_name
    ON helios_traces (name);

CREATE INDEX IF NOT EXISTS idx_helios_traces_start_time
    ON helios_traces (start_time DESC);

CREATE INDEX IF NOT EXISTS idx_helios_traces_user_id
    ON helios_traces (user_id);

CREATE INDEX IF NOT EXISTS idx_helios_traces_session_id
    ON helios_traces (session_id);

CREATE INDEX IF NOT EXISTS idx_helios_traces_model_id
    ON helios_traces (model_id);

CREATE INDEX IF NOT EXISTS idx_helios_traces_group_id
    ON helios_traces (group_id);

CREATE INDEX IF NOT EXISTS idx_helios_traces_labels
    ON helios_traces USING GIN (labels);

CREATE TABLE IF NOT EXISTS helios_spans (
    id          UUID            PRIMARY KEY,
    trace_id    UUID            NOT NULL REFERENCES helios_traces(id) ON DELETE CASCADE,
    parent_id   UUID,
    name        VARCHAR(255)    NOT NULL,
    kind        VARCHAR(50)     NOT NULL,
    start_time  TIMESTAMPTZ     NOT NULL,
    end_time    TIMESTAMPTZ,
    error       TEXT,
    attributes  JSONB           NOT NULL DEFAULT '{}',
    input_tokens INT,
    output_tokens INT,
    cache_creation_tokens INT,
    cache_read_tokens INT,
    cost_micro_usd BIGINT,
    CONSTRAINT fk_spans_parent FOREIGN KEY (trace_id, parent_id)
        REFERENCES helios_spans(trace_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_spans_trace_id UNIQUE (trace_id, id)
);

CREATE INDEX IF NOT EXISTS idx_helios_spans_trace_id
    ON helios_spans(trace_id);

CREATE TABLE IF NOT EXISTS helios_annotations (
    id          UUID            PRIMARY KEY,
    subject_id  UUID            NOT NULL,
    facet       VARCHAR(255),
    label       VARCHAR(255)    NOT NULL,
    author_kind VARCHAR(32)     NOT NULL,
    author_id   VARCHAR(255),
    rating      SMALLINT,
    comment     TEXT,
    metadata    JSONB           NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_annotations_subject
    ON helios_annotations(subject_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_helios_annotations_subject_facet_label_author
    ON helios_annotations(subject_id, COALESCE(facet, ''), label, author_id)
    WHERE author_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS helios_archive (
    id          UUID            PRIMARY KEY,
    agent_id    VARCHAR(255)    NOT NULL,
    content     TEXT            NOT NULL,
    metadata    JSONB           NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_archive_agent_created
    ON helios_archive (agent_id, created_at DESC);

CREATE TABLE IF NOT EXISTS helios_sessions (
    id              UUID            PRIMARY KEY,
    agent_id        VARCHAR(255)    NOT NULL,
    user_id         VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL,
    last_active_at  TIMESTAMPTZ     NOT NULL
);

CREATE TABLE IF NOT EXISTS helios_messages (
    id          UUID            PRIMARY KEY,
    session_id  UUID            NOT NULL REFERENCES helios_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(20)     NOT NULL,
    content     TEXT,
    tool_calls  JSONB,
    tool_call_id VARCHAR(255),
    tool_name   VARCHAR(255),
    metadata    JSONB           NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_helios_messages_session_id
    ON helios_messages (session_id);

CREATE INDEX IF NOT EXISTS idx_helios_sessions_agent_user
    ON helios_sessions (agent_id, user_id, last_active_at DESC);

CREATE TABLE IF NOT EXISTS helios_core_blocks (
    agent_id     VARCHAR(255)    NOT NULL,
    block_name   VARCHAR(255)    NOT NULL,
    description  TEXT,
    data         JSONB           NOT NULL DEFAULT '{}',
    max_size     INT             NOT NULL,
    created_at   TIMESTAMPTZ     NOT NULL,
    updated_at   TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (agent_id, block_name)
);

CREATE TABLE IF NOT EXISTS helios_agent_runs (
    run_id              UUID            PRIMARY KEY,
    session_id          UUID,
    agent_id            VARCHAR(255),
    user_id             VARCHAR(255),
    status              VARCHAR(20)     NOT NULL,
    iteration           INT             NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ     NOT NULL,
    last_checkpoint_at  TIMESTAMPTZ     NOT NULL,
    ended_at            TIMESTAMPTZ,
    error               TEXT
);

CREATE INDEX IF NOT EXISTS idx_helios_agent_runs_status_checkpoint
    ON helios_agent_runs (status, last_checkpoint_at DESC);

CREATE INDEX IF NOT EXISTS idx_helios_agent_runs_session
    ON helios_agent_runs (session_id);

CREATE TABLE IF NOT EXISTS helios_tool_calls (
    run_id        UUID            NOT NULL REFERENCES helios_agent_runs(run_id) ON DELETE CASCADE,
    tool_call_id  VARCHAR(255)    NOT NULL,
    iteration     INT             NOT NULL,
    tool_name     VARCHAR(255)    NOT NULL,
    args          JSONB,
    status        VARCHAR(20)     NOT NULL,
    output        TEXT,
    error         TEXT,
    started_at    TIMESTAMPTZ     NOT NULL,
    ended_at      TIMESTAMPTZ,
    PRIMARY KEY (run_id, tool_call_id)
);

CREATE INDEX IF NOT EXISTS idx_helios_tool_calls_status
    ON helios_tool_calls (run_id, status);
