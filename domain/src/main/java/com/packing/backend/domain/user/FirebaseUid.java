package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;

public record FirebaseUid(String value) {

    private static final int MAX_LENGTH = 128;

    public FirebaseUid {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Firebase uid must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                                                   "Firebase uid must be at most " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
