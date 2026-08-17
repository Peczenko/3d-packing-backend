package com.packing.backend.core.user.port.in;

import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;

import java.util.Optional;
import java.util.UUID;

public interface LoadUserAuthorizationUseCase {

    Optional<UserAuthorization> loadAuthorization(String firebaseUid);

    record UserAuthorization(UUID userId, UserRole role, UserStatus status) {

        public boolean isActive() {
            return status == UserStatus.ACTIVE;
        }
    }
}
