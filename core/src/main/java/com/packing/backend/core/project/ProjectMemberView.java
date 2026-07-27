package com.packing.backend.core.project;

import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.user.User;

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

    public static ProjectMemberView of(ProjectMember member, User user) {
        return new ProjectMemberView(
                member.userId().value(),
                user.username().value(),
                user.displayName(),
                member.permission(),
                member.addedAt());
    }
}
