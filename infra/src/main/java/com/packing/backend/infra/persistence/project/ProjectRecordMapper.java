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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
                toInstant(record.getCreatedAt()),
                toInstant(record.getUpdatedAt()),
                toInstant(record.getDeletedAt()),
                members);
    }

    static ProjectMember toDomain(ProjectMembersRecord record) {
        return new ProjectMember(
                new UserId(record.getUserId()),
                ProjectPermission.valueOf(record.getPermission()),
                new UserId(record.getAddedBy()),
                toInstant(record.getAddedAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
