package com.packing.backend.core.user.port.in;

import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the authorisation facts for an authenticated Firebase identity, read from the
 * database on every request.
 *
 * <p>This exists so that the {@code roles} custom claim in a Firebase ID token is never
 * trusted for authorisation. That claim is a snapshot taken when the token was minted:
 * revoking refresh tokens does not invalidate an ID token already in the client's hands,
 * so a demoted administrator would otherwise keep administrative access until it expired.
 * The database is the source of truth; the claim is a convenience mirror for clients.
 */
public interface LoadUserAuthorizationUseCase {

    /**
     * @return empty if no local profile exists yet — a legitimate state for a Firebase
     *         identity that has never called the API, which the just-in-time provisioning
     *         path resolves on its first request
     */
    Optional<UserAuthorization> loadAuthorization(String firebaseUid);

    record UserAuthorization(UUID userId, UserRole role, UserStatus status) {

        public boolean isActive() {
            return status == UserStatus.ACTIVE;
        }
    }
}
