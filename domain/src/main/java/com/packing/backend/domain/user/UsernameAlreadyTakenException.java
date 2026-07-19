package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.ResourceConflictException;

public class UsernameAlreadyTakenException extends ResourceConflictException {

    public UsernameAlreadyTakenException(Username username) {
        super("Username is already taken: " + username);
    }
}
