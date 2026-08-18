package com.packing.backend.infra.persistence.project;

import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import org.jooq.Condition;
import org.jooq.Field;

import java.util.List;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.when;
import static org.jooq.impl.DSL.multiset;
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

    static final Field<Integer> STATUS_RANK = when(PROJECTS.STATUS.eq(ProjectStatus.ACTIVE), 0)
                                                                 .when(PROJECTS.STATUS.eq(ProjectStatus.DISABLED), 1)
                                                                 .otherwise(2);

    private ProjectQueries() {
    }

    static Condition notDeleted() {
        return PROJECTS.STATUS.ne(ProjectStatus.DELETED);
    }

    static Condition memberIs(UserId userId) {
        return PROJECT_MEMBERS.USER_ID.eq(userId);
    }

    static Field<Integer> permissionRank(Field<ProjectPermission> permission) {
        return when(permission.eq(ProjectPermission.READ), 0)
                                                       .when(permission.eq(ProjectPermission.WRITE), 1)
                                                       .otherwise(2);
    }
}
