package com.packing.backend.core.user.port.in;

public interface DeleteUserAccountUseCase {

    /** Removes both the local profile and the Firebase identity. */
    void deleteAccount(String firebaseUid);
}
