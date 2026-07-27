package com.packing.backend.domain.project;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.UUID;

public record ProjectId(UUID value) {

    public ProjectId {
        if (value == null) {
            throw new DomainRuleViolationException("Project id must not be null");
        }
    }

    public static ProjectId generate() {
        return new ProjectId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
