package com.packing.backend.infra.persistence.packing;

import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.infra.persistence.jooq.tables.records.PackingJobsRecord;
import com.packing.backend.infra.persistence.shared.AggregateTable;
import org.jooq.Field;
import org.jooq.JSONB;

import java.util.Set;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;

final class PackingJobRecordMapper {

    static final AggregateTable<PackingJobsRecord> TABLE = new AggregateTable<>(
                                                                                "PackingJob",
                                                                                PACKING_JOBS.VERSION,
                                                                                Set.<Field<?>>of(
                                                                                                 PACKING_JOBS.PROJECT_ID,
                                                                                                 PACKING_JOBS.CREATED_BY,
                                                                                                 PACKING_JOBS.SPEC_JSON,
                                                                                                 PACKING_JOBS.MAX_RUNTIME_SECONDS,
                                                                                                 PACKING_JOBS.CREATED_AT));

    private PackingJobRecordMapper() {
    }

    static PackingJob toDomain(PackingJobsRecord record) {
        return PackingJob.rehydrate(
                                    record.getId(),
                                    record.getProjectId(),
                                    record.getCreatedBy(),
                                    record.getSpecJson()
                                          .data(),
                                    record.getMaxRuntimeSeconds(),
                                    record.getStatus(),
                                    record.getEngineVersion(),
                                    record.getEngineChecksumSha256(),
                                    record.getDispatchedAt(),
                                    record.getStartedAt(),
                                    record.getFinishedAt(),
                                    record.getResultFileName(),
                                    record.getResultContentType(),
                                    record.getResultChecksumSha256(),
                                    record.getResultSizeBytes(),
                                    record.getFailureReason(),
                                    record.getVersion(),
                                    record.getCreatedAt());
    }

    static PackingJobsRecord toRecord(PackingJob job) {
        PackingJobsRecord record = new PackingJobsRecord();
        record.setId(job.id());
        record.setProjectId(job.projectId());
        record.setCreatedBy(job.requestedBy());
        record.setSpecJson(JSONB.valueOf(job.specJson()));
        record.setMaxRuntimeSeconds(job.maxRuntimeSeconds());
        record.setStatus(job.status());
        record.setEngineVersion(job.engineVersion());
        record.setEngineChecksumSha256(job.engineChecksum());
        record.setDispatchedAt(job.dispatchedAt());
        record.setStartedAt(job.startedAt());
        record.setFinishedAt(job.finishedAt());
        record.setFailureReason(job.failureReason());
        record.setResultFileName(job.resultFileName());
        record.setResultContentType(job.resultContentType());
        record.setResultSizeBytes(job.resultSizeBytes());
        record.setResultChecksumSha256(job.resultChecksum());
        record.setVersion(job.version());
        record.setCreatedAt(job.createdAt());
        return record;
    }
}
