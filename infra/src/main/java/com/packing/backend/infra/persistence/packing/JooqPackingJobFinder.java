package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.PackingJobListCriteria;
import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;
import static com.packing.backend.infra.persistence.packing.PackingJobQueries.SEARCH_TEXT;
import static com.packing.backend.infra.persistence.packing.PackingJobQueries.STATUS_RANK;
import static org.jooq.impl.DSL.lower;

@Repository
@RequiredArgsConstructor
public class JooqPackingJobFinder implements PackingJobFinder {

    private final DSLContext dsl;

    @Override
    public Page<PackingJobView> listInProject(ProjectId projectId, PackingJobListCriteria criteria) {
        Condition condition = PackingJobQueries.inProject(projectId);
        if (criteria.search() != null) {
            condition = condition.and(SEARCH_TEXT.contains(criteria.search()
                                                                   .toLowerCase(Locale.ROOT)));
        }
        if (!criteria.statuses()
                     .isEmpty()) {
            condition = condition.and(PACKING_JOBS.STATUS.in(criteria.statuses()));
        }
        condition = withRange(condition,
                              PACKING_JOBS.CREATED_AT,
                              criteria.createdAt()
                                      .from(),
                              criteria.createdAt()
                                      .before());
        condition = withRange(condition,
                              PACKING_JOBS.STARTED_AT,
                              criteria.startedAt()
                                      .from(),
                              criteria.startedAt()
                                      .before());
        condition = withRange(condition,
                              PACKING_JOBS.FINISHED_AT,
                              criteria.finishedAt()
                                      .from(),
                              criteria.finishedAt()
                                      .before());

        List<org.jooq.SortField<?>> orderBy = List.of(
                                                      order(primarySort(criteria.sort()), criteria.direction(), isNullable(criteria.sort())),
                                                      order(PACKING_JOBS.ID, criteria.direction(), false));
        return Paging.fetch(dsl,
                            dsl.select(PackingJobQueries.VIEW_FIELDS)
                               .from(PACKING_JOBS)
                               .where(condition),
                            orderBy,
                            criteria.page(),
                            PackingJobQueries::toView);
    }

    private static Field<?> primarySort(PackingJobListCriteria.SortField sort) {
        return switch (sort) {
            case STATUS -> STATUS_RANK;
            case MAX_RUNTIME_SECONDS -> PACKING_JOBS.MAX_RUNTIME_SECONDS;
            case ENGINE_VERSION -> lower(PACKING_JOBS.ENGINE_VERSION);
            case CREATED_AT -> PACKING_JOBS.CREATED_AT;
            case STARTED_AT -> PACKING_JOBS.STARTED_AT;
            case FINISHED_AT -> PACKING_JOBS.FINISHED_AT;
            case RESULT_FILE_NAME -> lower(PACKING_JOBS.RESULT_FILE_NAME);
            case RESULT_SIZE_BYTES -> PACKING_JOBS.RESULT_SIZE_BYTES;
        };
    }

    private static boolean isNullable(PackingJobListCriteria.SortField sort) {
        return switch (sort) {
            case ENGINE_VERSION, STARTED_AT, FINISHED_AT, RESULT_FILE_NAME, RESULT_SIZE_BYTES -> true;
            default -> false;
        };
    }

    private static org.jooq.SortField<?> order(Field<?> field, SortDirection direction, boolean nullsLast) {
        org.jooq.SortField<?> ordered = direction == SortDirection.ASC ? field.asc() : field.desc();
        return nullsLast ? ordered.nullsLast() : ordered;
    }

    private static <T> Condition withRange(Condition condition, Field<T> field, T from, T before) {
        if (from != null) {
            condition = condition.and(field.ge(from));
        }
        if (before != null) {
            condition = condition.and(field.lt(before));
        }
        return condition;
    }

    @Override
    public Optional<PackingJobView> detailInProject(ProjectId projectId, PackingJobId jobId) {
        return dsl.select(PackingJobQueries.VIEW_FIELDS)
                  .from(PACKING_JOBS)
                  .where(PackingJobQueries.inProject(projectId)
                                          .and(PACKING_JOBS.ID.eq(jobId)))
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

    @Override
    public List<PackingJobId> findRunning(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        return dsl.select(PACKING_JOBS.ID)
                  .from(PACKING_JOBS)
                  .where(PACKING_JOBS.STATUS.eq(PackingJobStatus.RUNNING))
                  .orderBy(PACKING_JOBS.STARTED_AT.asc(), PACKING_JOBS.ID.asc())
                  .limit(limit)
                  .fetch(PACKING_JOBS.ID);
    }
}
