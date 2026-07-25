package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.UUID;

/** Strongly-typed identifier, so a UserId can never be passed where another id is expected. */
public record UserId(UUID value) {

    public UserId {
        if (value == null) {
            throw new DomainRuleViolationException("User id must not be null");
        }
    }

    public static UserId generate() {
        return new UserId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
