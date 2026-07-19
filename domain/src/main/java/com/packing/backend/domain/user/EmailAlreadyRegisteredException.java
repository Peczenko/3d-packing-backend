package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.ResourceConflictException;

public class EmailAlreadyRegisteredException extends ResourceConflictException {

    public EmailAlreadyRegisteredException(Email email) {
        super("Email is already registered: " + email);
    }
}
