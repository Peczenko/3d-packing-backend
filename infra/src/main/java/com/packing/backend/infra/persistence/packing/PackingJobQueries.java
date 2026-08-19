package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.domain.project.ProjectId;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.when;

final class PackingJobQueries {

    static final Field<?>[] VIEW_FIELDS = {
            PACKING_JOBS.ID, PACKING_JOBS.PROJECT_ID, PACKING_JOBS.STATUS,
            PACKING_JOBS.MAX_RUNTIME_SECONDS, PACKING_JOBS.ENGINE_VERSION,
            PACKING_JOBS.ENGINE_CHECKSUM_SHA256, PACKING_JOBS.CREATED_AT,
            PACKING_JOBS.STARTED_AT, PACKING_JOBS.FINISHED_AT,
            PACKING_JOBS.FAILURE_REASON, PACKING_JOBS.RESULT_FILE_NAME,
            PACKING_JOBS.RESULT_CONTENT_TYPE, PACKING_JOBS.RESULT_SIZE_BYTES,
            PACKING_JOBS.RESULT_CHECKSUM_SHA256 };

    static final Field<String> SEARCH_TEXT = lower(
                                                   coalesce(PACKING_JOBS.ENGINE_VERSION, inline(""))
                                                                                                    .concat(inline(" "))
                                                                                                    .concat(coalesce(PACKING_JOBS.RESULT_FILE_NAME, inline("")))
                                                                                                    .concat(inline(" "))
                                                                                                    .concat(coalesce(PACKING_JOBS.FAILURE_REASON, inline(""))));

    static final Field<Integer> STATUS_RANK = when(PACKING_JOBS.STATUS.eq(com.packing.backend.domain.packing.PackingJobStatus.QUEUED), 0)
                                                                                                                                         .when(PACKING_JOBS.STATUS.eq(com.packing.backend.domain.packing.PackingJobStatus.RUNNING),
                                                                                                                                               1)
                                                                                                                                         .when(PACKING_JOBS.STATUS.eq(com.packing.backend.domain.packing.PackingJobStatus.SUCCEEDED),
                                                                                                                                               2)
                                                                                                                                         .otherwise(3);

    private PackingJobQueries() {
    }

    static Condition inProject(ProjectId projectId) {
        return PACKING_JOBS.PROJECT_ID.eq(projectId);
    }

    static PackingJobView toView(Record record) {
        return new PackingJobView(
                                  record.get(PACKING_JOBS.ID)
                                        .value(),
                                  record.get(PACKING_JOBS.PROJECT_ID)
                                        .value(),
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
                                  record.get(PACKING_JOBS.RESULT_CHECKSUM_SHA256));
    }
}
