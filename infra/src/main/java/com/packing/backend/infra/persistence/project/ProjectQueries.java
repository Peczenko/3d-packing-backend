package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import org.jooq.Condition;
import org.jooq.Field;

import java.util.List;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectFrom;

final class ProjectQueries {

    static final Field<List<ProjectMember>> MEMBERS = multiset(
                                                               selectFrom(PROJECT_MEMBERS)
                                                                                          .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                                                                                          .orderBy(PROJECT_MEMBERS.ADDED_AT.asc(), PROJECT_MEMBERS.USER_ID.asc()))
                                                                                                                                                                  .convertFrom(rows -> rows.map(ProjectRecordMapper::toDomain));

    static final Field<Integer> MEMBER_COUNT = field(selectCount()
                                                                  .from(PROJECT_MEMBERS)
                                                                  .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID)));

    static final Field<List<ProjectMemberView>> MEMBER_VIEWS = multiset(
                                                                        select(PROJECT_MEMBERS.USER_ID,
                                                                               USERS.USERNAME,
                                                                               USERS.DISPLAY_NAME,
                                                                               PROJECT_MEMBERS.PERMISSION,
                                                                               PROJECT_MEMBERS.ADDED_AT)
                                                                                                        .from(PROJECT_MEMBERS)
                                                                                                        .join(USERS)
                                                                                                        .on(USERS.ID.eq(PROJECT_MEMBERS.USER_ID))
                                                                                                        .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                                                                                                        .orderBy(PROJECT_MEMBERS.ADDED_AT.asc(), PROJECT_MEMBERS.USER_ID.asc()))
                                                                                                                                                                                .convertFrom(rows -> rows.map(row -> new ProjectMemberView(
                                                                                                                                                                                                                                           row.get(PROJECT_MEMBERS.USER_ID)
                                                                                                                                                                                                                                              .value(),
                                                                                                                                                                                                                                           row.get(USERS.USERNAME),
                                                                                                                                                                                                                                           row.get(USERS.DISPLAY_NAME),
                                                                                                                                                                                                                                           row.get(PROJECT_MEMBERS.PERMISSION),
                                                                                                                                                                                                                                           row.get(PROJECT_MEMBERS.ADDED_AT))));

    private ProjectQueries() {
    }

    static Condition notDeleted() {
        return PROJECTS.STATUS.ne(ProjectStatus.DELETED);
    }

    static Condition memberIs(UserId userId) {
        return PROJECT_MEMBERS.USER_ID.eq(userId);
    }
}
