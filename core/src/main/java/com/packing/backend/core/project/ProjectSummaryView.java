package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectSummaryView(UUID id,
                                 String name,
                                 ProjectStatus status,
                                 ProjectPermission myPermission,
                                 int memberCount,
                                 Instant createdAt,
                                 Instant updatedAt) {
}
