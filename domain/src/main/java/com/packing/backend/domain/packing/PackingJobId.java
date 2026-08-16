package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.UUID;

public record PackingJobId(UUID value) {

    public PackingJobId {
        if (value == null) {
            throw new DomainRuleViolationException("Packing job id must not be null");
        }
    }

    public static PackingJobId generate() {
        return new PackingJobId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
