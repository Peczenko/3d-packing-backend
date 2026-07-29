package com.packing.backend.infra.persistence.project;

import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.jooq.tables.records.ProjectMembersRecord;
import com.packing.backend.infra.persistence.jooq.tables.records.ProjectsRecord;
import com.packing.backend.infra.persistence.shared.Timestamps;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;

/**
 * jOOQ surfaces {@code timestamp with time zone} as {@link OffsetDateTime}, while the
 * domain speaks {@link Instant}; everything is normalised to UTC on the way out.
 */
final class ProjectRecordMapper {

    private ProjectRecordMapper() {
    }

    static Project toDomain(ProjectsRecord record, Collection<ProjectMember> members) {
        return Project.rehydrate(
                new ProjectId(record.getId()),
                new ProjectName(record.getName()),
                new UserId(record.getCreatedBy()),
                ProjectStatus.valueOf(record.getStatus()),
                record.getVersion(),
                Timestamps.toInstant(record.getCreatedAt()),
                Timestamps.toInstant(record.getUpdatedAt()),
                Timestamps.toInstant(record.getDeletedAt()),
                members);
    }

    static ProjectMember toDomain(ProjectMembersRecord record) {
        return new ProjectMember(
                new UserId(record.getUserId()),
                ProjectPermission.valueOf(record.getPermission()),
                new UserId(record.getAddedBy()),
                Timestamps.toInstant(record.getAddedAt()));
    }

    static ProjectsRecord toRecord(Project project) {
        ProjectsRecord record = new ProjectsRecord();
        record.setId(project.id().value());
        record.setName(project.name().value());
        record.setCreatedBy(project.createdBy().value());
        record.setStatus(project.status().name());
        record.setVersion(project.version());
        record.setCreatedAt(Timestamps.toOffsetDateTime(project.createdAt()));
        record.setUpdatedAt(Timestamps.toOffsetDateTime(project.updatedAt()));
        record.setDeletedAt(Timestamps.toOffsetDateTime(project.deletedAt()));
        return record;
    }

    static ProjectMembersRecord toRecord(ProjectId projectId, ProjectMember member) {
        ProjectMembersRecord record = new ProjectMembersRecord();
        record.setProjectId(projectId.value());
        record.setUserId(member.userId().value());
        record.setPermission(member.permission().name());
        record.setAddedBy(member.addedBy().value());
        record.setAddedAt(Timestamps.toOffsetDateTime(member.addedAt()));
        return record;
    }
}
