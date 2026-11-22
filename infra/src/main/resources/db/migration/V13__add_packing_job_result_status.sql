ALTER TABLE packing_jobs
    ADD COLUMN bin_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN result_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

UPDATE packing_jobs SET bin_count = jsonb_array_length(result_bins) WHERE result_bins IS NOT NULL;
UPDATE packing_jobs SET result_status = 'READY' WHERE result_checksum_sha256 IS NOT NULL;

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_bin_count
    CHECK (bin_count >= 0
       AND (bin_count = 0 OR status = 'SUCCEEDED')
       AND (result_bins IS NULL OR jsonb_array_length(result_bins) = bin_count));

ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_result_status
    CHECK (result_status IN ('NONE', 'PENDING', 'READY', 'FAILED')
       AND (result_status = 'NONE' OR status = 'SUCCEEDED')
       AND (result_status = 'READY') = (result_checksum_sha256 IS NOT NULL));
