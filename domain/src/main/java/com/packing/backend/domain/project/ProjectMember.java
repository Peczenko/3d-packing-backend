package com.packing.backend.domain.project;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

/**
 * One user's standing in one project. Immutable: changing a permission replaces the record
 * rather than mutating it, so a member is always a consistent snapshot.
 */
public record ProjectMember(UserId userId,
                            ProjectPermission permission,
                            UserId addedBy,
                            Instant addedAt) {

    public ProjectMember {
        if (userId == null) {
            throw new DomainRuleViolationException("Project member must have a user id");
        }
        if (permission == null) {
            throw new DomainRuleViolationException("Project member must have a permission");
        }
        if (addedBy == null) {
            throw new DomainRuleViolationException("Project member must record who added them");
        }
        if (addedAt == null) {
            throw new DomainRuleViolationException("Project member must record when they were added");
        }
    }

    public boolean isOwner() {
        return permission == ProjectPermission.OWNER;
    }

    /** Keeps {@code addedBy} and {@code addedAt} — they record the original grant. */
    ProjectMember withPermission(ProjectPermission newPermission) {
        return new ProjectMember(userId, newPermission, addedBy, addedAt);
    }
}
