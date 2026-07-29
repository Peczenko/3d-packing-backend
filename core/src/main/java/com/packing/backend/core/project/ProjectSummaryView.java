package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Listing entry. Omits the member roster, which is the expensive part and is only wanted on
 * a single project's detail view.
 */
public record ProjectSummaryView(UUID id,
                                 String name,
                                 ProjectStatus status,
                                 ProjectPermission myPermission,
                                 int memberCount,
                                 Instant createdAt,
                                 Instant updatedAt) {
}
