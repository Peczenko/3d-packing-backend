package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String status,
        UUID createdBy,
        String myPermission,
        List<ProjectMemberResponse> members,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(ProjectView view) {
        return new ProjectResponse(
                view.id(),
                view.name(),
                view.status().name(),
                view.createdBy(),
                view.myPermission().name(),
                view.members().stream().map(ProjectMemberResponse::from).toList(),
                view.createdAt(),
                view.updatedAt());
    }
}
