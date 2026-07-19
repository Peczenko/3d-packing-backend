package com.packing.backend.infra.firebase;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.user.port.out.FirebaseUserDirectory;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Firebase Admin SDK adapter. Firebase SDK types stop here — {@link FirebaseAuthException}
 * is translated into {@link ExternalServiceException} so that nothing above {@code :infra}
 * has to know Firebase exists.
 */
class FirebaseAdminUserDirectory implements FirebaseUserDirectory {

    /**
     * Custom claim name. Must match what the JWT authorities converter in {@code :app}
     * reads out of the ID token.
     */
    static final String ROLES_CLAIM = "roles";

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminUserDirectory.class);

    private final FirebaseAuth firebaseAuth;

    FirebaseAdminUserDirectory(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public void assignRole(FirebaseUid uid, UserRole role) {
        call("assign role " + role, uid,
                () -> firebaseAuth.setCustomUserClaims(uid.value(), Map.of(ROLES_CLAIM, List.of(role.name()))));
    }

    @Override
    public void revokeRefreshTokens(FirebaseUid uid) {
        call("revoke refresh tokens for", uid, () -> firebaseAuth.revokeRefreshTokens(uid.value()));
    }

    /**
     * Deleting an identity Firebase no longer has is treated as success: the caller's goal
     * — that the identity not exist — is already met, and failing here would roll back an
     * otherwise complete account deletion.
     */
    @Override
    public void delete(FirebaseUid uid) {
        try {
            firebaseAuth.deleteUser(uid.value());
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                log.info("Firebase user {} was already absent; treating deletion as complete", uid);
                return;
            }
            throw asExternalServiceException("delete", uid, e);
        }
    }

    private void call(String operation, FirebaseUid uid, FirebaseCall action) {
        try {
            action.execute();
        } catch (FirebaseAuthException e) {
            throw asExternalServiceException(operation, uid, e);
        }
    }

    private ExternalServiceException asExternalServiceException(
            String operation, FirebaseUid uid, FirebaseAuthException cause) {
        return new ExternalServiceException(
                "firebase",
                "Failed to " + operation + " Firebase user " + uid + ": " + cause.getMessage(),
                cause);
    }

    @FunctionalInterface
    private interface FirebaseCall {
        void execute() throws FirebaseAuthException;
    }
}
