package com.packing.backend.infra.firebase;

import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.user.port.out.FirebaseUserDirectory;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserRole;

/**
 * Stands in when {@code firebase.admin-enabled=false} — CI and local development without
 * service-account credentials.
 *
 * <p>Fails loudly rather than silently no-op'ing: pretending a role was mirrored into
 * Firebase when it was not would let a test pass that should not. ID-token verification is
 * unaffected, since that needs only the public JWKS.
 */
class UnavailableFirebaseUserDirectory implements FirebaseUserDirectory {

    @Override
    public void assignRole(FirebaseUid uid, UserRole role) {
        throw unavailable("assign a role");
    }

    @Override
    public void revokeRefreshTokens(FirebaseUid uid) {
        throw unavailable("revoke refresh tokens");
    }

    @Override
    public void delete(FirebaseUid uid) {
        throw unavailable("delete a user");
    }

    private ExternalServiceException unavailable(String operation) {
        return new ExternalServiceException(
                "firebase",
                "Cannot " + operation + ": the Firebase Admin SDK is disabled "
                        + "(firebase.admin-enabled=false)");
    }
}
