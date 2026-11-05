ALTER TABLE packing_jobs
    ALTER COLUMN spec_json DROP NOT NULL,
    ADD COLUMN input_schema_version INTEGER,
    ADD COLUMN items JSONB,
    ADD COLUMN parameters JSONB;

UPDATE packing_jobs
   SET status         = 'FAILED',
       failure_reason = 'Superseded by structured job input',
       finished_at    = now(),
       version        = version + 1
 WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_input_shape CHECK (
       (spec_json IS NOT NULL AND input_schema_version IS NULL
        AND items IS NULL AND parameters IS NULL)
    OR (spec_json IS NULL
        AND input_schema_version IS NOT NULL AND input_schema_version >= 1
        AND items IS NOT NULL AND jsonb_typeof(items) = 'array'
        AND parameters IS NOT NULL AND jsonb_typeof(parameters) = 'object'));

CREATE INDEX ix_packing_jobs_project_creator_created
    ON packing_jobs (project_id, created_by, created_at DESC, id DESC)
    WHERE items IS NOT NULL;
