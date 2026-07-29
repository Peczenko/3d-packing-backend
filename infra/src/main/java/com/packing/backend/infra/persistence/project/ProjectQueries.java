package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.shared.Timestamps;
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

/**
 * The predicates and the nested-roster projection shared by every read of a project, so that
 * "a project you can still see" means one thing in one place.
 */
final class ProjectQueries {

    /**
     * The member roster, correlated to whichever {@code PROJECTS} row the enclosing query is
     * producing, so a project and its members arrive in a single statement.
     *
     * <p>jOOQ emulates {@code MULTISET} on PostgreSQL by aggregating the subquery into JSONB
     * and parsing it back. That costs a little CPU at both ends, which is a good trade here
     * for two reasons: it removes a round trip, and — more importantly — it makes the roster
     * consistent with the row it belongs to. Two separate statements take two snapshots under
     * {@code READ COMMITTED}, so a membership change landing between them would return a
     * project whose members contradict the {@code version} it was read at, and that version
     * is what the optimistic lock writes back on.
     *
     * <p>Correlation is by the generated singleton table, so a query that aliases
     * {@code PROJECTS} cannot use this field.
     */
    static final Field<List<ProjectMember>> MEMBERS = multiset(
            selectFrom(PROJECT_MEMBERS)
                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                    // Grant order, so the aggregate rehydrates members deterministically.
                    .orderBy(PROJECT_MEMBERS.ADDED_AT.asc(), PROJECT_MEMBERS.USER_ID.asc()))
            .convertFrom(rows -> rows.map(ProjectRecordMapper::toDomain));

    static final Field<Integer> MEMBER_COUNT = field(selectCount()
            .from(PROJECT_MEMBERS)
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID)));

    /**
     * The roster joined to the identities a client renders, correlated to the enclosing
     * PROJECTS row. Carries no email: membership of a shared project is not a reason to hand
     * every other member an address.
     */
    static final Field<List<ProjectMemberView>> MEMBER_VIEWS = multiset(
            select(PROJECT_MEMBERS.USER_ID, USERS.USERNAME, USERS.DISPLAY_NAME,
                    PROJECT_MEMBERS.PERMISSION, PROJECT_MEMBERS.ADDED_AT)
                    .from(PROJECT_MEMBERS)
                    .join(USERS).on(USERS.ID.eq(PROJECT_MEMBERS.USER_ID))
                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                    .orderBy(PROJECT_MEMBERS.ADDED_AT.asc(), PROJECT_MEMBERS.USER_ID.asc()))
            .convertFrom(rows -> rows.map(row -> new ProjectMemberView(
                    row.get(PROJECT_MEMBERS.USER_ID),
                    row.get(USERS.USERNAME),
                    row.get(USERS.DISPLAY_NAME),
                    ProjectPermission.valueOf(row.get(PROJECT_MEMBERS.PERMISSION)),
                    Timestamps.toInstant(row.get(PROJECT_MEMBERS.ADDED_AT)))));

    private ProjectQueries() {
    }

    /** Deletion is a tombstone, so every read has to exclude it explicitly. */
    static Condition notDeleted() {
        return PROJECTS.STATUS.ne(ProjectStatus.DELETED.name());
    }

    static Condition memberIs(UserId userId) {
        return PROJECT_MEMBERS.USER_ID.eq(userId.value());
    }
}
