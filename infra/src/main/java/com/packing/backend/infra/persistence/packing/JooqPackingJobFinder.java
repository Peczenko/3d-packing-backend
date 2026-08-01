package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;

@Repository
@RequiredArgsConstructor
public class JooqPackingJobFinder implements PackingJobFinder {

    private final DSLContext dsl;

    @Override
    public Page<PackingJobView> listInProject(ProjectId projectId, PageRequest page) {
        return Paging.fetch(dsl,
                dsl.select(PACKING_JOBS.ID, PACKING_JOBS.PROJECT_ID, PACKING_JOBS.STATUS,
                                PACKING_JOBS.MAX_RUNTIME_SECONDS, PACKING_JOBS.ENGINE_VERSION,
                                PACKING_JOBS.ENGINE_CHECKSUM_SHA256, PACKING_JOBS.CREATED_AT,
                                PACKING_JOBS.STARTED_AT, PACKING_JOBS.FINISHED_AT,
                                PACKING_JOBS.FAILURE_REASON, PACKING_JOBS.RESULT_FILE_NAME,
                                PACKING_JOBS.RESULT_CONTENT_TYPE, PACKING_JOBS.RESULT_SIZE_BYTES,
                                PACKING_JOBS.RESULT_CHECKSUM_SHA256)
                        .from(PACKING_JOBS)
                        .where(PACKING_JOBS.PROJECT_ID.eq(projectId)),
                List.of(PACKING_JOBS.CREATED_AT.desc(), PACKING_JOBS.ID.desc()),
                page,
                record -> new PackingJobView(
                        record.get(PACKING_JOBS.ID).value(),
                        record.get(PACKING_JOBS.PROJECT_ID).value(),
                        record.get(PACKING_JOBS.STATUS),
                        record.get(PACKING_JOBS.MAX_RUNTIME_SECONDS),
                        record.get(PACKING_JOBS.ENGINE_VERSION),
                        record.get(PACKING_JOBS.ENGINE_CHECKSUM_SHA256),
                        record.get(PACKING_JOBS.CREATED_AT),
                        record.get(PACKING_JOBS.STARTED_AT),
                        record.get(PACKING_JOBS.FINISHED_AT),
                        record.get(PACKING_JOBS.FAILURE_REASON),
                        record.get(PACKING_JOBS.RESULT_FILE_NAME),
                        record.get(PACKING_JOBS.RESULT_CONTENT_TYPE),
                        record.get(PACKING_JOBS.RESULT_SIZE_BYTES),
                        record.get(PACKING_JOBS.RESULT_CHECKSUM_SHA256)));
    }

    @Override
    public Optional<PackingJobView> detailInProject(ProjectId projectId, PackingJobId jobId) {
        return dsl.select(PACKING_JOBS.ID, PACKING_JOBS.PROJECT_ID, PACKING_JOBS.STATUS,
                        PACKING_JOBS.MAX_RUNTIME_SECONDS, PACKING_JOBS.ENGINE_VERSION,
                        PACKING_JOBS.ENGINE_CHECKSUM_SHA256, PACKING_JOBS.CREATED_AT,
                        PACKING_JOBS.STARTED_AT, PACKING_JOBS.FINISHED_AT,
                        PACKING_JOBS.FAILURE_REASON, PACKING_JOBS.RESULT_FILE_NAME,
                        PACKING_JOBS.RESULT_CONTENT_TYPE, PACKING_JOBS.RESULT_SIZE_BYTES,
                        PACKING_JOBS.RESULT_CHECKSUM_SHA256)
                .from(PACKING_JOBS)
                .where(PACKING_JOBS.PROJECT_ID.eq(projectId).and(PACKING_JOBS.ID.eq(jobId)))
                .fetchOptional(record -> new PackingJobView(
                        record.get(PACKING_JOBS.ID).value(),
                        record.get(PACKING_JOBS.PROJECT_ID).value(),
                        record.get(PACKING_JOBS.STATUS),
                        record.get(PACKING_JOBS.MAX_RUNTIME_SECONDS),
                        record.get(PACKING_JOBS.ENGINE_VERSION),
                        record.get(PACKING_JOBS.ENGINE_CHECKSUM_SHA256),
                        record.get(PACKING_JOBS.CREATED_AT),
                        record.get(PACKING_JOBS.STARTED_AT),
                        record.get(PACKING_JOBS.FINISHED_AT),
                        record.get(PACKING_JOBS.FAILURE_REASON),
                        record.get(PACKING_JOBS.RESULT_FILE_NAME),
                        record.get(PACKING_JOBS.RESULT_CONTENT_TYPE),
                        record.get(PACKING_JOBS.RESULT_SIZE_BYTES),
                        record.get(PACKING_JOBS.RESULT_CHECKSUM_SHA256)));
    }
}
