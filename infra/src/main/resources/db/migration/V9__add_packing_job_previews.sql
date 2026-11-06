ALTER TABLE packing_jobs
    ADD COLUMN preview_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN preview_models JSONB;

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_preview_status
    CHECK (preview_status IN ('NONE', 'PENDING', 'READY', 'FAILED'));

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_preview_models
    CHECK ((preview_status = 'READY')
         = (preview_models IS NOT NULL AND jsonb_typeof(preview_models) = 'array'));
