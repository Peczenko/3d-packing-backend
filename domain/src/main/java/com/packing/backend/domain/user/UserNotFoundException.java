package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(String message) {
        super(message);
    }

    public static UserNotFoundException byId(UserId id) {
        return new UserNotFoundException("No user with id " + id);
    }

    public static UserNotFoundException byFirebaseUid(FirebaseUid uid) {
        return new UserNotFoundException("No user with Firebase uid " + uid);
    }
}
