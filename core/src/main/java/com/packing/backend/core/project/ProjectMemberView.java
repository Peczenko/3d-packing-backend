package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectPermission;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberView(UUID userId,
        String username,
        String displayName,
        ProjectPermission permission,
        Instant addedAt) {
}
