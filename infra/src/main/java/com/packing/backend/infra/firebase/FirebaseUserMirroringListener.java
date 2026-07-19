package com.packing.backend.infra.firebase;

import com.packing.backend.core.user.port.out.FirebaseUserDirectory;
import com.packing.backend.domain.user.event.UserAccountDeleted;
import com.packing.backend.domain.user.event.UserRoleChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Propagates committed user changes to Firebase.
 *
 * <p>Everything here runs {@link TransactionPhase#AFTER_COMMIT}, which is the whole point.
 * PostgreSQL and Firebase cannot share a transaction, so calling Firebase <em>inside</em>
 * one means a later rollback silently leaves the external change in place — for a role
 * change that is a privilege escalation: the database reverts to {@code USER} while
 * Firebase keeps advertising {@code ADMIN}. Committing first makes the database
 * unambiguously the source of truth and leaves Firebase as a mirror that can only ever lag.
 *
 * <p>Failures are logged, never rethrown. The request has already succeeded and the
 * database is correct; authorisation reads the database, so a stale claim grants nothing.
 * An {@code ERROR} here means a Firebase record needs reconciling, not that the operation
 * failed.
 */
@Component
class FirebaseUserMirroringListener {

    private static final Logger log = LoggerFactory.getLogger(FirebaseUserMirroringListener.class);

    private final FirebaseUserDirectory firebaseDirectory;

    FirebaseUserMirroringListener(FirebaseUserDirectory firebaseDirectory) {
        this.firebaseDirectory = firebaseDirectory;
    }

    /**
     * Mirrors the role into a custom claim and revokes refresh tokens so the client picks
     * up a fresh one. Advisory only: the claim is for the client's own UI decisions and is
     * never trusted by this backend.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRoleChanged(UserRoleChanged event) {
        try {
            firebaseDirectory.assignRole(event.firebaseUid(), event.newRole());
            firebaseDirectory.revokeRefreshTokens(event.firebaseUid());
        } catch (RuntimeException e) {
            log.error("Role change for user {} committed as {} but could not be mirrored to "
                            + "Firebase uid {}. Authorization is unaffected (it reads the "
                            + "database); the Firebase claim needs reconciling.",
                    event.userId(), event.newRole(), event.firebaseUid(), e);
        }
    }

    /**
     * Removes the Firebase identity after the local profile has been tombstoned.
     *
     * <p>If this fails the account is still effectively deleted: the tombstone row causes
     * the authorisation lookup to reject every token for that uid, so the orphaned Firebase
     * identity cannot reach the API.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAccountDeleted(UserAccountDeleted event) {
        try {
            firebaseDirectory.delete(event.firebaseUid());
        } catch (RuntimeException e) {
            log.error("Profile {} was deleted locally but Firebase identity {} could not be "
                            + "removed. Access is already blocked by the tombstone; the "
                            + "orphaned Firebase identity needs removing.",
                    event.userId(), event.firebaseUid(), e);
        }
    }
}
