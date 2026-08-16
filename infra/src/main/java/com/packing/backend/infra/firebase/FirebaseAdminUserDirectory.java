package com.packing.backend.infra.firebase;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserRole;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class FirebaseAdminUserDirectory implements FirebaseUserDirectory {

    static final String ROLES_CLAIM = "roles";

    private final FirebaseAuth firebaseAuth;

    @Override
    public void assignRole(FirebaseUid uid, UserRole role) {
        call("assign role " + role,
             uid,
             () -> firebaseAuth.setCustomUserClaims(uid.value(), Map.of(ROLES_CLAIM, List.of(role.name()))));
    }

    @Override
    public void revokeRefreshTokens(FirebaseUid uid) {
        call("revoke refresh tokens for", uid, () -> firebaseAuth.revokeRefreshTokens(uid.value()));
    }

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
