package com.packing.backend.api.user;

import com.packing.backend.core.user.UserView;

import java.time.Instant;
import java.util.UUID;

/**
 * REST representation of a user.
 *
 * <p>Separate from {@link UserView} on purpose: the wire format is a public contract with
 * its own compatibility obligations, and the internal read model should be free to change
 * without breaking clients.
 */
public record UserResponse(
        UUID id,
        String email,
        String username,
        String displayName,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    public static UserResponse from(UserView view) {
        return new UserResponse(
                view.id(),
                view.email(),
                view.username(),
                view.displayName(),
                view.role().name(),
                view.status().name(),
                view.createdAt(),
                view.updatedAt(),
                view.lastLoginAt());
    }
}
