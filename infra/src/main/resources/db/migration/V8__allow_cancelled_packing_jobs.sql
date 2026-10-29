ALTER TABLE packing_jobs DROP CONSTRAINT packing_jobs_status_check;
ALTER TABLE packing_jobs ADD CONSTRAINT ck_packing_jobs_status
    CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
