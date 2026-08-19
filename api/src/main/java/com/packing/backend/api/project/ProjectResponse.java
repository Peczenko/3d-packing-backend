package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectView;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        ProjectStatus status,
        UUID createdBy,
        ProjectPermission myPermission,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(ProjectView view) {
        return new ProjectResponse(
                                   view.id(),
                                   view.name(),
                                   view.status(),
                                   view.createdBy(),
                                   view.myPermission(),
                                   view.createdAt(),
                                   view.updatedAt());
    }
}
