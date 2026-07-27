package com.packing.backend.infra.persistence.project;

import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import org.jooq.Condition;
import org.jooq.Field;

import java.util.List;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static org.jooq.impl.DSL.multiset;
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
