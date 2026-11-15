ALTER TABLE packing_jobs ADD COLUMN preview_bins JSONB;

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_preview_bins
    CHECK (preview_bins IS NULL
        OR (preview_status = 'READY' AND jsonb_typeof(preview_bins) = 'array' AND jsonb_array_length(preview_bins) > 1));
