package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.project.port.out.ProjectFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBER_COUNT;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBER_VIEWS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.memberIs;
import static com.packing.backend.infra.persistence.project.ProjectQueries.notDeleted;

@Repository
@RequiredArgsConstructor
public class JooqProjectFinder implements ProjectFinder {

    private final DSLContext dsl;

    @Override
    public Page<ProjectSummaryView> listForMember(UserId caller, PageRequest page) {
        return Paging.fetch(dsl,
                dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.STATUS,
                                PROJECT_MEMBERS.PERMISSION, MEMBER_COUNT,
                                PROJECTS.CREATED_AT, PROJECTS.UPDATED_AT)
                        .from(PROJECTS)
                        .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                        .where(memberIs(caller).and(notDeleted())),
                List.of(PROJECTS.CREATED_AT.desc(), PROJECTS.ID.desc()),
                page,
                JooqProjectFinder::toSummary);
    }

    @Override
    public Optional<ProjectView> detailFor(UserId caller, ProjectId projectId) {
        return dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.STATUS, PROJECTS.CREATED_BY,
                        PROJECT_MEMBERS.PERMISSION, MEMBER_VIEWS,
                        PROJECTS.CREATED_AT, PROJECTS.UPDATED_AT)
                .from(PROJECTS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                .where(PROJECTS.ID.eq(projectId)
                        .and(memberIs(caller))
                        .and(notDeleted()))
                .fetchOptional()
                .map(JooqProjectFinder::toDetail);
    }

    private static ProjectSummaryView toSummary(Record row) {
        return new ProjectSummaryView(
                row.get(PROJECTS.ID).value(),
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
                row.get(PROJECTS.ID).value(),
                row.get(PROJECTS.NAME),
                row.get(PROJECTS.STATUS),
                row.get(PROJECTS.CREATED_BY).value(),
                row.get(PROJECT_MEMBERS.PERMISSION),
                members,
                row.get(PROJECTS.CREATED_AT),
                row.get(PROJECTS.UPDATED_AT));
    }
}
