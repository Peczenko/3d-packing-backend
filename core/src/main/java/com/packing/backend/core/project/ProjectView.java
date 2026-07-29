package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param myPermission the requesting caller's level, so a client can hide actions it would
 *                     only be refused for
 */
public record ProjectView(UUID id,
                          String name,
                          ProjectStatus status,
                          UUID createdBy,
                          ProjectPermission myPermission,
                          List<ProjectMemberView> members,
                          Instant createdAt,
                          Instant updatedAt) {
}
