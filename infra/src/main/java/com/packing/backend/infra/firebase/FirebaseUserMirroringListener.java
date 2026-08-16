package com.packing.backend.infra.firebase;

import com.packing.backend.domain.user.event.UserAccountDeleted;
import com.packing.backend.domain.user.event.UserRoleChanged;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class FirebaseUserMirroringListener {

    private final FirebaseUserDirectory firebaseDirectory;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRoleChanged(UserRoleChanged event) {
        try {
            firebaseDirectory.assignRole(event.firebaseUid(), event.newRole());
            firebaseDirectory.revokeRefreshTokens(event.firebaseUid());
        } catch (RuntimeException e) {
            log.error("Role change for user {} committed as {} but could not be mirrored to "
                    + "Firebase uid {}. Authorization is unaffected (it reads the "
                    + "database); the Firebase claim needs reconciling.",
                      event.userId(),
                      event.newRole(),
                      event.firebaseUid(),
                      e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAccountDeleted(UserAccountDeleted event) {
        try {
            firebaseDirectory.delete(event.firebaseUid());
        } catch (RuntimeException e) {
            log.error("Profile {} was deleted locally but Firebase identity {} could not be "
                    + "removed. Access is already blocked by the tombstone; the "
                    + "orphaned Firebase identity needs removing.",
                      event.userId(),
                      event.firebaseUid(),
                      e);
        }
    }
}
