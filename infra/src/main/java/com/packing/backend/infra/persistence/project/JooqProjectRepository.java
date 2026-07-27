package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.springframework.stereotype.Repository;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;

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
                .set(PROJECTS.CREATED_AT, ProjectRecordMapper.toOffsetDateTime(project.createdAt()))
                .set(PROJECTS.UPDATED_AT, ProjectRecordMapper.toOffsetDateTime(project.updatedAt()))
                .set(PROJECTS.DELETED_AT, ProjectRecordMapper.toOffsetDateTime(project.deletedAt()))
                .onConflict(PROJECTS.ID)
                .doUpdate()
                // id, created_by and created_at are immutable in the domain, so they are
                // deliberately absent from the update set.
                .set(PROJECTS.NAME, project.name().value())
                .set(PROJECTS.STATUS, project.status().name())
                .set(PROJECTS.VERSION, expectedVersion + 1)
                .set(PROJECTS.UPDATED_AT, ProjectRecordMapper.toOffsetDateTime(project.updatedAt()))
                .set(PROJECTS.DELETED_AT, ProjectRecordMapper.toOffsetDateTime(project.deletedAt()))
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
        return dsl.selectFrom(PROJECTS)
                .where(PROJECTS.ID.eq(id.value()))
                .fetchOptional()
                .map(record -> ProjectRecordMapper.toDomain(record, membersOf(List.of(id.value()))
                        .getOrDefault(id.value(), List.of())));
    }

    /**
     * Ordered newest first with the id as a tiebreak, so paging stays stable when two
     * projects share a timestamp. Members for the whole page are fetched in one follow-up
     * query rather than one per project.
     */
    @Override
    public List<Project> findByMember(UserId userId, int offset, int limit) {
        List<Record> rows = dsl.select(PROJECTS.fields())
                .from(PROJECTS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                .where(PROJECT_MEMBERS.USER_ID.eq(userId.value())
                        .and(PROJECTS.STATUS.ne(ProjectStatus.DELETED.name())))
                .orderBy(PROJECTS.CREATED_AT.desc(), PROJECTS.ID.desc())
                .offset(offset)
                .limit(limit)
                .fetch();

        List<UUID> ids = rows.stream().map(row -> row.get(PROJECTS.ID)).toList();
        Map<UUID, List<ProjectMember>> members = membersOf(ids);

        return rows.stream()
                .map(row -> ProjectRecordMapper.toDomain(
                        row.into(PROJECTS),
                        members.getOrDefault(row.get(PROJECTS.ID), List.of())))
                .toList();
    }

    @Override
    public long countByMember(UserId userId) {
        return dsl.fetchCount(dsl.select(PROJECTS.ID)
                .from(PROJECTS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                .where(PROJECT_MEMBERS.USER_ID.eq(userId.value())
                        .and(PROJECTS.STATUS.ne(ProjectStatus.DELETED.name()))));
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
                            ProjectRecordMapper.toOffsetDateTime(member.addedAt())));
        }
        dsl.batch(inserts).execute();
    }

    /** Ordered by {@code added_at} so the aggregate rehydrates members in grant order. */
    private Map<UUID, List<ProjectMember>> membersOf(List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.in(projectIds))
                .orderBy(PROJECT_MEMBERS.ADDED_AT.asc(), PROJECT_MEMBERS.USER_ID.asc())
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(
                        record -> record.get(PROJECT_MEMBERS.PROJECT_ID),
                        Collectors.mapping(ProjectRecordMapper::toDomain, Collectors.toList())));
    }
}
