package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
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
                dsl.select(PackingJobQueries.VIEW_FIELDS)
                        .from(PACKING_JOBS)
                        .where(PackingJobQueries.inProject(projectId)),
                List.of(PACKING_JOBS.CREATED_AT.desc(), PACKING_JOBS.ID.desc()),
                page,
                PackingJobQueries::toView);
    }

    @Override
    public Optional<PackingJobView> detailInProject(ProjectId projectId, PackingJobId jobId) {
        return dsl.select(PackingJobQueries.VIEW_FIELDS)
                .from(PACKING_JOBS)
                .where(PackingJobQueries.inProject(projectId).and(PACKING_JOBS.ID.eq(jobId)))
                .fetchOptional(PackingJobQueries::toView);
    }

    @Override
    public List<PackingJobId> findUndispatched(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        return dsl.select(PACKING_JOBS.ID)
                .from(PACKING_JOBS)
                .where(PACKING_JOBS.STATUS.eq(PackingJobStatus.QUEUED)
                        .and(PACKING_JOBS.DISPATCHED_AT.isNull()))
                .orderBy(PACKING_JOBS.CREATED_AT.asc(), PACKING_JOBS.ID.asc())
                .limit(limit)
                .fetch(PACKING_JOBS.ID);
    }
}
