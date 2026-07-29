package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectPermission;

import java.time.Instant;
import java.util.UUID;

/**
 * A member joined with the identity fields a client needs to render them. Carries no email:
 * membership of a shared project is not a reason to hand every other member an address.
 */
public record ProjectMemberView(UUID userId,
                                String username,
                                String displayName,
                                ProjectPermission permission,
                                Instant addedAt) {
}
