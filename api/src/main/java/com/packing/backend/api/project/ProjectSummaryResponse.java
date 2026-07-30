package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectSummaryView;

import java.time.Instant;
import java.util.UUID;

public record ProjectSummaryResponse(
        UUID id,
        String name,
        String status,
        String myPermission,
        int memberCount,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectSummaryResponse from(ProjectSummaryView view) {
        return new ProjectSummaryResponse(
                view.id(),
                view.name(),
                view.status().name(),
                view.myPermission().name(),
                view.memberCount(),
                view.createdAt(),
                view.updatedAt());
    }
}
