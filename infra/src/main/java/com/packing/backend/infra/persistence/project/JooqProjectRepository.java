package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.shared.Timestamps;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBERS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.memberIs;
import static com.packing.backend.infra.persistence.project.ProjectQueries.notDeleted;

/**
 * No {@code @Transactional} here: transaction boundaries belong to the application services
 * in {@code :core}, and this adapter always runs inside one of theirs. That matters more than
 * usual for {@link #save}, which writes two tables and must not be observed half-applied.
 */
@Repository
@RequiredArgsConstructor
public class JooqProjectRepository implements ProjectRepository {

    private final DSLContext dsl;


    /**
     * Upsert on the primary key, guarded by the aggregate's version, followed by a full
     * replacement of the member rows.
     *
     * <p>Delete-then-insert rather than a diff: membership is small, and the optimistic lock
     * on {@code projects.version} is what actually serialises two concurrent edits. Because
     * the version guard runs first, a stale caller never reaches the member rewrite.
     */
    @Override
    public Project save(Project project) {
        long expectedVersion = project.version();
        int affected = dsl.insertInto(PROJECTS)
                .set(PROJECTS.ID, project.id().value())
                .set(PROJECTS.NAME, project.name().value())
                .set(PROJECTS.CREATED_BY, project.createdBy().value())
                .set(PROJECTS.STATUS, project.status().name())
                // Both branches store expectedVersion + 1 so a write always advances the
                // version by exactly one, whether it inserted or updated.
                .set(PROJECTS.VERSION, expectedVersion + 1)
                .set(PROJECTS.CREATED_AT, Timestamps.toOffsetDateTime(project.createdAt()))
                .set(PROJECTS.UPDATED_AT, Timestamps.toOffsetDateTime(project.updatedAt()))
                .set(PROJECTS.DELETED_AT, Timestamps.toOffsetDateTime(project.deletedAt()))
                .onConflict(PROJECTS.ID)
                .doUpdate()
                // id, created_by and created_at are immutable in the domain, so they are
                // deliberately absent from the update set.
                .set(PROJECTS.NAME, project.name().value())
                .set(PROJECTS.STATUS, project.status().name())
                .set(PROJECTS.VERSION, expectedVersion + 1)
                .set(PROJECTS.UPDATED_AT, Timestamps.toOffsetDateTime(project.updatedAt()))
                .set(PROJECTS.DELETED_AT, Timestamps.toOffsetDateTime(project.deletedAt()))
                .where(PROJECTS.VERSION.eq(expectedVersion))
                .execute();

        if (affected == 0) {
            throw new ConcurrentUpdateException(
                    "Project " + project.id() + " was modified by another transaction "
                            + "(expected version " + expectedVersion + "). Re-read and retry.");
        }

        replaceMembers(project);
        project.markPersisted();
        return project;
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        return dsl.select(PROJECTS.asterisk(), MEMBERS)
                .from(PROJECTS)
                .where(PROJECTS.ID.eq(id.value()))
                .fetchOptional()
                .map(JooqProjectRepository::toProject);
    }

    /**
     * Ordered newest first with the id as a tiebreak, so paging stays stable when two
     * projects share a timestamp.
     *
     * <p>The join to {@code PROJECT_MEMBERS} filters and nothing more — the roster itself
     * arrives through {@link ProjectQueries#MEMBERS}. It cannot multiply rows, because
     * {@code (project_id, user_id)} is the primary key, so one user matches at most once per
     * project.
     */
    @Override
    public List<Project> findByMember(UserId userId, int offset, int limit) {
        return dsl.select(PROJECTS.asterisk(), MEMBERS)
                .from(PROJECTS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                .where(memberIs(userId).and(notDeleted()))
                .orderBy(PROJECTS.CREATED_AT.desc(), PROJECTS.ID.desc())
                .offset(offset)
                .limit(limit)
                .fetch(JooqProjectRepository::toProject);
    }

    @Override
    public long countByMember(UserId userId) {
        return dsl.fetchCount(dsl.select(PROJECTS.ID)
                .from(PROJECTS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                .where(memberIs(userId).and(notDeleted())));
    }

    private static Project toProject(Record row) {
        return ProjectRecordMapper.toDomain(row.into(PROJECTS), row.get(MEMBERS));
    }

    private void replaceMembers(Project project) {
        dsl.deleteFrom(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(project.id().value()))
                .execute();

        List<ProjectMember> members = project.members();
        if (members.isEmpty()) {
            return;
        }

        List<Query> inserts = new ArrayList<>(members.size());
        for (ProjectMember member : members) {
            inserts.add(dsl.insertInto(PROJECT_MEMBERS)
                    .set(PROJECT_MEMBERS.PROJECT_ID, project.id().value())
                    .set(PROJECT_MEMBERS.USER_ID, member.userId().value())
                    .set(PROJECT_MEMBERS.PERMISSION, member.permission().name())
                    .set(PROJECT_MEMBERS.ADDED_BY, member.addedBy().value())
                    .set(PROJECT_MEMBERS.ADDED_AT,
                            Timestamps.toOffsetDateTime(member.addedAt())));
        }
        dsl.batch(inserts).execute();
    }

}
