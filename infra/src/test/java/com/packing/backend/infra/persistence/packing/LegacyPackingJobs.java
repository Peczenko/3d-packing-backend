package com.packing.backend.infra.persistence.packing;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import java.time.Instant;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;

final class LegacyPackingJobs {

    private LegacyPackingJobs() {
    }

    static PackingJobId insert(DSLContext dsl, ProjectId project, UserId user, PackingJobStatus status, Instant createdAt) {
        PackingJobId id = PackingJobId.generate();
        dsl.insertInto(PACKING_JOBS)
           .set(PACKING_JOBS.ID, id)
           .set(PACKING_JOBS.PROJECT_ID, project)
           .set(PACKING_JOBS.CREATED_BY, user)
           .set(PACKING_JOBS.SPEC_JSON, JSONB.valueOf("{\"testField\":42}"))
           .set(PACKING_JOBS.MAX_RUNTIME_SECONDS, 60L)
           .set(PACKING_JOBS.STATUS, status)
           .set(PACKING_JOBS.VERSION, 0L)
           .set(PACKING_JOBS.CREATED_AT, createdAt)
           .execute();
        return id;
    }
}
