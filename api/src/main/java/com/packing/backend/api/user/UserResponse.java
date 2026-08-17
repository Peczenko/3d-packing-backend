package com.packing.backend.api.user;

import com.packing.backend.core.user.UserView;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String username,
        String displayName,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    public static UserResponse from(UserView view) {
        return new UserResponse(
                                view.id(),
                                view.email(),
                                view.username(),
                                view.displayName(),
                                view.role(),
                                view.status(),
                                view.createdAt(),
                                view.updatedAt(),
                                view.lastLoginAt());
    }
}
