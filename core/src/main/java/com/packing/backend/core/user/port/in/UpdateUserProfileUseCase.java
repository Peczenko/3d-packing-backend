package com.packing.backend.core.user.port.in;

import com.packing.backend.core.user.UserView;

public interface UpdateUserProfileUseCase {

    UserView updateProfile(UpdateUserProfileCommand command);

    /** @param displayName may be null, which clears it */
    record UpdateUserProfileCommand(String firebaseUid, String username, String displayName) {
    }
}
