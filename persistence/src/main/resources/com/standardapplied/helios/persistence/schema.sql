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
    id                VARCHAR(64)     PRIMARY KEY,
    otel_trace_id     CHAR(32)        NOT NULL,
    otel_span_id      CHAR(16)        NOT NULL,
    name              TEXT            NOT NULL,
    start_time        TIMESTAMPTZ     NOT NULL,
    end_time          TIMESTAMPTZ,
    error             TEXT,
    attributes        JSONB           NOT NULL DEFAULT '{}',
    input_text        TEXT,
    output_text       TEXT,
    session_id        VARCHAR(128),
    model_id          VARCHAR(255),
    total_tokens      INT             NOT NULL DEFAULT 0,
    input_tokens      INT,
    output_tokens     INT,
    cache_creation_tokens INT,
    cache_read_tokens INT,
    cost_micro_usd    BIGINT,
    thumbs_up_count   INT             NOT NULL DEFAULT 0,
    thumbs_down_count INT             NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_helios_traces_name
    ON helios_traces (name);

CREATE INDEX IF NOT EXISTS idx_helios_traces_start_time
    ON helios_traces (start_time DESC);

CREATE INDEX IF NOT EXISTS idx_helios_traces_session_id
    ON helios_traces (session_id);

CREATE INDEX IF NOT EXISTS idx_helios_traces_model_id
    ON helios_traces (model_id);

CREATE TABLE IF NOT EXISTS helios_spans (
    id          CHAR(16)        PRIMARY KEY,
    run_id      VARCHAR(64)     NOT NULL,
    name        TEXT            NOT NULL,
    kind        VARCHAR(50)     NOT NULL,
    start_time  TIMESTAMPTZ     NOT NULL,
    end_time    TIMESTAMPTZ,
    error       TEXT,
    attributes  JSONB           NOT NULL DEFAULT '{}',
    total_tokens INT,
    input_tokens INT,
    output_tokens INT,
    cache_creation_tokens INT,
    cache_read_tokens INT,
    cost_micro_usd BIGINT
);

CREATE INDEX IF NOT EXISTS idx_helios_spans_run_id
    ON helios_spans(run_id);

CREATE TABLE IF NOT EXISTS helios_annotations (
    id          UUID            PRIMARY KEY,
    subject_id  VARCHAR(64)     NOT NULL,
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

CREATE TABLE IF NOT EXISTS helios_session_entries (
    session_id  VARCHAR(128)  NOT NULL,
    seq         BIGINT        NOT NULL,
    type        VARCHAR(16)   NOT NULL,
    run_id      VARCHAR(64)   NOT NULL,
    entry       TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_helios_session_entries_replaced
    ON helios_session_entries (session_id, seq) WHERE type = 'replaced';
