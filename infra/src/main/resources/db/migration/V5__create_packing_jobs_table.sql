CREATE TABLE packing_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    created_by UUID NOT NULL REFERENCES users(id),
    spec_json JSONB NOT NULL,
    max_runtime_seconds BIGINT NOT NULL CHECK (max_runtime_seconds BETWEEN 1 AND 7200),
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    engine_version VARCHAR(255),
    engine_checksum_sha256 VARCHAR(64),
    dispatched_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_reason TEXT,
    result_file_name VARCHAR(255),
    result_content_type VARCHAR(255),
    result_size_bytes BIGINT CHECK (result_size_bytes IS NULL OR result_size_bytes > 0),
    result_checksum_sha256 VARCHAR(64),
    version BIGINT NOT NULL CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_packing_jobs_engine_checksum
        CHECK (engine_checksum_sha256 IS NULL OR engine_checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_packing_jobs_result_checksum
        CHECK (result_checksum_sha256 IS NULL OR result_checksum_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_packing_jobs_project_created
    ON packing_jobs(project_id, created_at DESC, id DESC);
CREATE INDEX ix_packing_jobs_undispatched
    ON packing_jobs(created_at, id)
    WHERE status = 'QUEUED' AND dispatched_at IS NULL;
