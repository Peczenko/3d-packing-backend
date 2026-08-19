package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectView(UUID id,
        String name,
        ProjectStatus status,
        UUID createdBy,
        ProjectPermission myPermission,
        Instant createdAt,
        Instant updatedAt) {
}
