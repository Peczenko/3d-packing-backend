package com.packing.backend.core.file.port.out;

import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

public interface FileOwnerLookup {

    /** Empty if there is no profile for the uid, or the profile is not active. */
    Optional<UserId> findActiveOwner(FirebaseUid firebaseUid);
}
