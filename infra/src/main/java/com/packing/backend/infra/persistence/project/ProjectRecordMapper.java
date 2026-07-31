package com.packing.backend.infra.persistence.project;

import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.infra.persistence.jooq.tables.records.ProjectMembersRecord;
import com.packing.backend.infra.persistence.jooq.tables.records.ProjectsRecord;
import com.packing.backend.infra.persistence.shared.AggregateTable;
import org.jooq.Field;

import java.util.Collection;
import java.util.Set;

import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;

final class ProjectRecordMapper {

    static final AggregateTable<ProjectsRecord> TABLE = new AggregateTable<>(
            "Project", PROJECTS.VERSION, Set.<Field<?>>of(PROJECTS.CREATED_BY, PROJECTS.CREATED_AT));

    private ProjectRecordMapper() {
    }

    static Project toDomain(ProjectsRecord record, Collection<ProjectMember> members) {
        return Project.rehydrate(
                record.getId(),
                new ProjectName(record.getName()),
                record.getCreatedBy(),
                record.getStatus(),
                record.getVersion(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                record.getDeletedAt(),
                members);
    }

    static ProjectMember toDomain(ProjectMembersRecord record) {
        return new ProjectMember(
                record.getUserId(),
                record.getPermission(),
                record.getAddedBy(),
                record.getAddedAt());
    }

    static ProjectsRecord toRecord(Project project) {
        ProjectsRecord record = new ProjectsRecord();
        record.setId(project.id());
        record.setName(project.name().value());
        record.setCreatedBy(project.createdBy());
        record.setStatus(project.status());
        record.setVersion(project.version());
        record.setCreatedAt(project.createdAt());
        record.setUpdatedAt(project.updatedAt());
        record.setDeletedAt(project.deletedAt());
        return record;
    }

    static ProjectMembersRecord toRecord(ProjectId projectId, ProjectMember member) {
        ProjectMembersRecord record = new ProjectMembersRecord();
        record.setProjectId(projectId);
        record.setUserId(member.userId());
        record.setPermission(member.permission());
        record.setAddedBy(member.addedBy());
        record.setAddedAt(member.addedAt());
        return record;
    }
}
