ALTER TABLE packing_jobs ADD COLUMN result_bins JSONB;

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_result_bins
    CHECK (result_bins IS NULL
        OR (status = 'SUCCEEDED' AND jsonb_typeof(result_bins) = 'array' AND jsonb_array_length(result_bins) > 0));
