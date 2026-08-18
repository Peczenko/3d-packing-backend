package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.project.port.out.ProjectFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBER_COUNT;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBER_VIEWS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.PERMISSION_RANK;
import static com.packing.backend.infra.persistence.project.ProjectQueries.STATUS_RANK;
import static com.packing.backend.infra.persistence.project.ProjectQueries.memberIs;
import static com.packing.backend.infra.persistence.project.ProjectQueries.notDeleted;
import static org.jooq.impl.DSL.lower;

@Repository
@RequiredArgsConstructor
public class JooqProjectFinder implements ProjectFinder {

    private final DSLContext dsl;

    @Override
    public Page<ProjectSummaryView> listForMember(UserId caller, ProjectListCriteria criteria) {
        Condition condition = memberIs(caller).and(notDeleted());
        if (criteria.search() != null) {
            condition = condition.and(PROJECTS.NAME.containsIgnoreCase(criteria.search()));
        }
        if (!criteria.statuses().isEmpty()) {
            condition = condition.and(PROJECTS.STATUS.in(criteria.statuses()));
        }
        if (!criteria.permissions().isEmpty()) {
            condition = condition.and(PROJECT_MEMBERS.PERMISSION.in(criteria.permissions()));
        }
        condition = withRange(condition, PROJECTS.CREATED_AT, criteria.createdAt().from(), criteria.createdAt().before());
        condition = withRange(condition, PROJECTS.UPDATED_AT, criteria.updatedAt().from(), criteria.updatedAt().before());

        List<org.jooq.SortField<?>> orderBy = List.of(
                                                        order(primarySort(criteria.sort()), criteria.direction(), false),
                                                        order(PROJECTS.ID, criteria.direction(), false));
        return Paging.fetch(dsl,
                            dsl.select(PROJECTS.ID,
                                       PROJECTS.NAME,
                                       PROJECTS.STATUS,
                                       PROJECT_MEMBERS.PERMISSION,
                                       MEMBER_COUNT,
                                       PROJECTS.CREATED_AT,
                                       PROJECTS.UPDATED_AT)
                               .from(PROJECTS)
                               .join(PROJECT_MEMBERS)
                               .on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                               .where(condition),
                            orderBy,
                            criteria.page(),
                            JooqProjectFinder::toSummary);
    }

    private static Field<?> primarySort(ProjectListCriteria.SortField sort) {
        return switch (sort) {
            case NAME -> lower(PROJECTS.NAME);
            case STATUS -> STATUS_RANK;
            case PERMISSION -> PERMISSION_RANK;
            case MEMBER_COUNT -> MEMBER_COUNT;
            case CREATED_AT -> PROJECTS.CREATED_AT;
            case UPDATED_AT -> PROJECTS.UPDATED_AT;
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
    public Optional<ProjectView> detailFor(UserId caller, ProjectId projectId) {
        return dsl.select(PROJECTS.ID,
                          PROJECTS.NAME,
                          PROJECTS.STATUS,
                          PROJECTS.CREATED_BY,
                          PROJECT_MEMBERS.PERMISSION,
                          MEMBER_VIEWS,
                          PROJECTS.CREATED_AT,
                          PROJECTS.UPDATED_AT)
                  .from(PROJECTS)
                  .join(PROJECT_MEMBERS)
                  .on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                  .where(PROJECTS.ID.eq(projectId)
                                    .and(memberIs(caller))
                                    .and(notDeleted()))
                  .fetchOptional()
                  .map(JooqProjectFinder::toDetail);
    }

    private static ProjectSummaryView toSummary(Record row) {
        return new ProjectSummaryView(
                                      row.get(PROJECTS.ID)
                                         .value(),
                                      row.get(PROJECTS.NAME),
                                      row.get(PROJECTS.STATUS),
                                      row.get(PROJECT_MEMBERS.PERMISSION),
                                      row.get(MEMBER_COUNT),
                                      row.get(PROJECTS.CREATED_AT),
                                      row.get(PROJECTS.UPDATED_AT));
    }

    private static ProjectView toDetail(Record row) {
        List<ProjectMemberView> members = row.get(MEMBER_VIEWS);
        return new ProjectView(
                               row.get(PROJECTS.ID)
                                  .value(),
                               row.get(PROJECTS.NAME),
                               row.get(PROJECTS.STATUS),
                               row.get(PROJECTS.CREATED_BY)
                                  .value(),
                               row.get(PROJECT_MEMBERS.PERMISSION),
                               members,
                               row.get(PROJECTS.CREATED_AT),
                               row.get(PROJECTS.UPDATED_AT));
    }
}
