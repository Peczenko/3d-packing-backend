package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;

/**
 * The Firebase Authentication user id — the {@code sub} claim of a Firebase ID token and
 * the link between this system's user profile and the identity Firebase owns.
 *
 * <p>Firebase uids are opaque; the only documented guarantee is a maximum length of 128
 * characters, so this deliberately validates nothing beyond that.
 */
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
