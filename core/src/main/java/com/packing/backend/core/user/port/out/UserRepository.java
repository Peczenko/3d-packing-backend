package com.packing.backend.core.user.port.out;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;

import java.time.Instant;
import java.util.Optional;

/** Output port for user persistence. Implemented by the jOOQ adapter in {@code :infra}. */
public interface UserRepository {

    /**
     * Writes the whole aggregate, guarded by its version.
     *
     * @throws com.packing.backend.core.shared.ConcurrentUpdateException if the stored
     *         version no longer matches the one the aggregate was loaded with
     */
    User save(User user);

    /**
     * Narrow write for the sign-in path, which runs on every authenticated {@code /me}
     * call.
     *
     * <p>Deliberately not {@link #save(User)}: a full aggregate write would carry role,
     * status and username from a read that may already be stale, and would fail the
     * optimistic lock whenever two of the user's own requests overlap. Touching only the
     * three fields a sign-in actually owns is both safe and idempotent, so it neither
     * clobbers concurrent changes nor bumps the version.
     */
    void recordSignIn(UserId id, Email email, Instant updatedAt, Instant lastLoginAt);

    Optional<User> findById(UserId id);

    Optional<User> findByFirebaseUid(FirebaseUid firebaseUid);

    boolean existsByUsername(Username username);
}
