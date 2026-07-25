package com.packing.backend.infra.firebase;

import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserRole;

public interface FirebaseUserDirectory {

    void assignRole(FirebaseUid uid, UserRole role);

    void revokeRefreshTokens(FirebaseUid uid);

    void delete(FirebaseUid uid);
}
