package com.packing.backend.domain.project.event;

import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

/**
 * Raised when a user becomes a member of a project, not when an existing member's permission
 * changes — it exists to drive the welcome email, and being re-levelled is not a welcome.
 *
 * <p>Carries no email address: the aggregate does not know one, and the after-commit handler
 * that sends the notification can look it up.
 */
public record ProjectAccessGranted(ProjectId projectId,
                                   ProjectName projectName,
                                   UserId userId,
                                   ProjectPermission permission,
                                   UserId grantedBy,
                                   Instant occurredAt) implements DomainEvent {
}
