package com.packing.backend.domain.project;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

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

    ProjectMember withPermission(ProjectPermission newPermission) {
        return new ProjectMember(userId, newPermission, addedBy, addedAt);
    }
}
