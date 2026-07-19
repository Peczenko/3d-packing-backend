package com.packing.backend.core.user.port.out;

import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserRole;

/**
 * Output port for the Firebase-side user record, which this backend does not own but does
 * need to mutate.
 *
 * <p>Kept to the operations the use cases actually perform. Firebase SDK types never
 * cross this boundary — the adapter in {@code :infra} translates them, including
 * {@code FirebaseAuthException} into
 * {@link com.packing.backend.core.shared.ExternalServiceException}.
 */
public interface FirebaseUserDirectory {

    /**
     * Mirrors the role into a Firebase custom claim so that it appears in subsequently
     * issued ID tokens. The database remains the source of truth.
     */
    void assignRole(FirebaseUid uid, UserRole role);

    /**
     * Forces the client to obtain a fresh ID token. Custom claims set by
     * {@link #assignRole} only reach the client on the next token refresh, so a role
     * change is not effective until existing tokens are revoked.
     */
    void revokeRefreshTokens(FirebaseUid uid);

    /** Permanently removes the Firebase identity. */
    void delete(FirebaseUid uid);
}
