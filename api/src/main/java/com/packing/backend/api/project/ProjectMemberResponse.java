package com.packing.backend.api.project;

import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.domain.project.ProjectPermission;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberResponse(
        UUID userId,
        String username,
        String displayName,
        ProjectPermission permission,
        Instant addedAt) {

    public static ProjectMemberResponse from(ProjectMemberView view) {
        return new ProjectMemberResponse(
                                         view.userId(),
                                         view.username(),
                                         view.displayName(),
                                         view.permission(),
                                         view.addedAt());
    }
}
