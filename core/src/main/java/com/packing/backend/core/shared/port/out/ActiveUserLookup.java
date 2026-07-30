package com.packing.backend.core.shared.port.out;

import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

public interface ActiveUserLookup {

    Optional<UserId> findActiveUser(FirebaseUid firebaseUid);
}
