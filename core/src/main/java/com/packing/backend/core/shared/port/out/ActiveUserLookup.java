package com.packing.backend.core.shared.port.out;

import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

/**
 * Resolves an authenticated Firebase identity to the local profile id without loading the
 * whole aggregate — every request needs this, and almost none of them need the rest of the
 * user.
 */
public interface ActiveUserLookup {

    /** Empty if there is no profile for the uid, or the profile is not active. */
    Optional<UserId> findActiveUser(FirebaseUid firebaseUid);
}
