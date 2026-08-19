package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.core.project.ProjectMemberListCriteria;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.project.port.out.ProjectFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.jooq.tables.ProjectMembers;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBER_COUNT;
import static com.packing.backend.infra.persistence.project.ProjectQueries.STATUS_RANK;
import static com.packing.backend.infra.persistence.project.ProjectQueries.memberIs;
import static com.packing.backend.infra.persistence.project.ProjectQueries.notDeleted;
import static com.packing.backend.infra.persistence.project.ProjectQueries.permissionRank;
import static com.packing.backend.infra.persistence.shared.JooqConditions.instantRange;
import static org.jooq.impl.DSL.lower;

@Repository
@RequiredArgsConstructor
public class JooqProjectFinder implements ProjectFinder {

    private final DSLContext dsl;

    @Override
    public Page<ProjectSummaryView> listForMember(UserId caller, ProjectListCriteria criteria) {
        Condition condition = projectListCondition(caller, criteria);

        List<SortField<?>> orderBy = List.of(
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

    private static Condition projectListCondition(UserId caller, ProjectListCriteria criteria) {
        Condition condition = memberIs(caller).and(notDeleted());
        if (criteria.search() != null) {
            condition = condition.and(lower(PROJECTS.NAME).contains(criteria.search()
                                                                            .toLowerCase(Locale.ROOT)));
        }
        if (!criteria.statuses()
                     .isEmpty()) {
            condition = condition.and(PROJECTS.STATUS.in(criteria.statuses()));
        }
        if (!criteria.permissions()
                     .isEmpty()) {
            condition = condition.and(PROJECT_MEMBERS.PERMISSION.in(criteria.permissions()));
        }
        condition = condition.and(instantRange(PROJECTS.CREATED_AT, criteria.createdAt()));
        condition = condition.and(instantRange(PROJECTS.UPDATED_AT, criteria.updatedAt()));
        return condition;
    }

    private static Field<?> primarySort(ProjectListCriteria.SortField sort) {
        return switch (sort) {
            case NAME -> lower(PROJECTS.NAME);
            case STATUS -> STATUS_RANK;
            case PERMISSION -> permissionRank(PROJECT_MEMBERS.PERMISSION);
            case MEMBER_COUNT -> MEMBER_COUNT;
            case CREATED_AT -> PROJECTS.CREATED_AT;
            case UPDATED_AT -> PROJECTS.UPDATED_AT;
        };
    }

    private static SortField<?> order(Field<?> field, SortDirection direction, boolean nullsLast) {
        SortField<?> ordered = direction == SortDirection.ASC ? field.asc() : field.desc();
        return nullsLast ? ordered.nullsLast() : ordered;
    }

    @Override
    public Page<ProjectMemberView> listMembersFor(UserId caller,
                                                  ProjectId projectId,
                                                  ProjectMemberListCriteria criteria) {
        var members = PROJECT_MEMBERS.as("members");
        var callerMembership = PROJECT_MEMBERS.as("callerMembership");
        Condition condition = memberListCondition(caller, projectId, criteria, members, callerMembership);

        boolean displayName = criteria.sort() == ProjectMemberListCriteria.SortField.DISPLAY_NAME;
        List<SortField<?>> orderBy = List.of(
                                             order(memberPrimarySort(criteria.sort(), members), criteria.direction(), displayName),
                                             order(members.USER_ID, criteria.direction(), false));
        return Paging.fetch(dsl,
                            dsl.select(members.USER_ID,
                                       USERS.USERNAME,
                                       USERS.DISPLAY_NAME,
                                       members.PERMISSION,
                                       members.ADDED_AT)
                               .from(members)
                               .join(USERS)
                               .on(USERS.ID.eq(members.USER_ID))
                               .join(callerMembership)
                               .on(callerMembership.PROJECT_ID.eq(members.PROJECT_ID))
                               .join(PROJECTS)
                               .on(PROJECTS.ID.eq(members.PROJECT_ID))
                               .where(condition),
                            orderBy,
                            criteria.page(),
                            row -> toMember(row, members));
    }

    private static Condition memberListCondition(UserId caller,
                                                 ProjectId projectId,
                                                 ProjectMemberListCriteria criteria,
                                                 ProjectMembers members,
                                                 ProjectMembers callerMembership) {
        Condition condition = members.PROJECT_ID.eq(projectId)
                                                .and(callerMembership.PROJECT_ID.eq(members.PROJECT_ID))
                                                .and(callerMembership.USER_ID.eq(caller))
                                                .and(notDeleted());
        if (criteria.search() != null) {
            String search = criteria.search()
                                    .toLowerCase(Locale.ROOT);
            condition = condition.and(lower(USERS.USERNAME).contains(search)
                                                           .or(lower(USERS.DISPLAY_NAME).contains(search)));
        }
        if (!criteria.permissions()
                     .isEmpty()) {
            condition = condition.and(members.PERMISSION.in(criteria.permissions()));
        }
        return condition.and(instantRange(members.ADDED_AT, criteria.addedAt()));
    }

    private static Field<?> memberPrimarySort(ProjectMemberListCriteria.SortField sort,
                                              ProjectMembers members) {
        return switch (sort) {
            case USERNAME -> lower(USERS.USERNAME);
            case DISPLAY_NAME -> lower(USERS.DISPLAY_NAME);
            case PERMISSION -> permissionRank(members.PERMISSION);
            case ADDED_AT -> members.ADDED_AT;
        };
    }

    @Override
    public Optional<ProjectView> detailFor(UserId caller, ProjectId projectId) {
        return dsl.select(PROJECTS.ID,
                          PROJECTS.NAME,
                          PROJECTS.STATUS,
                          PROJECTS.CREATED_BY,
                          PROJECT_MEMBERS.PERMISSION,
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
        return new ProjectView(
                               row.get(PROJECTS.ID)
                                  .value(),
                               row.get(PROJECTS.NAME),
                               row.get(PROJECTS.STATUS),
                               row.get(PROJECTS.CREATED_BY)
                                  .value(),
                               row.get(PROJECT_MEMBERS.PERMISSION),
                               row.get(PROJECTS.CREATED_AT),
                               row.get(PROJECTS.UPDATED_AT));
    }

    private static ProjectMemberView toMember(Record row, ProjectMembers members) {
        return new ProjectMemberView(row.get(members.USER_ID)
                                        .value(),
                                     row.get(USERS.USERNAME),
                                     row.get(USERS.DISPLAY_NAME),
                                     row.get(members.PERMISSION),
                                     row.get(members.ADDED_AT));
    }
}
