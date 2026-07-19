package com.packing.backend.core.user;

import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model returned by the user use cases.
 *
 * <p>The aggregate itself never leaves {@code :core} — this is what stops a controller
 * from mutating domain state, and it keeps the REST contract from being coupled to the
 * aggregate's shape.
 */
public record UserView(
        UUID id,
        String firebaseUid,
        String email,
        String username,
        String displayName,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    public static UserView from(User user) {
        return new UserView(
                user.id().value(),
                user.firebaseUid().value(),
                user.email().value(),
                user.username().value(),
                user.displayName(),
                user.role(),
                user.status(),
                user.createdAt(),
                user.updatedAt(),
                user.lastLoginAt());
    }
}
