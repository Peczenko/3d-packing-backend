package com.packing.backend.core.user.port.out;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository {

    User save(User user);

    void recordSignIn(UserId id, Email email, Instant updatedAt, Instant lastLoginAt);

    Optional<User> findById(UserId id);

    Optional<User> findByFirebaseUid(FirebaseUid firebaseUid);

    boolean existsByUsername(Username username);
}
