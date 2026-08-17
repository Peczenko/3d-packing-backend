package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectSummaryResponse(
        UUID id,
        String name,
        ProjectStatus status,
        ProjectPermission myPermission,
        int memberCount,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectSummaryResponse from(ProjectSummaryView view) {
        return new ProjectSummaryResponse(
                                          view.id(),
                                          view.name(),
                                          view.status(),
                                          view.myPermission(),
                                          view.memberCount(),
                                          view.createdAt(),
                                          view.updatedAt());
    }
}
