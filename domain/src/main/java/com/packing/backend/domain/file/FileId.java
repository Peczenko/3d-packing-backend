package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.UUID;

public record FileId(UUID value) {

    public FileId {
        if (value == null) {
            throw new DomainRuleViolationException("File id must not be null");
        }
    }

    public static FileId generate() {
        return new FileId(UUID.randomUUID());
    }

    public static FileId of(String value) {
        try {
            return new FileId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new DomainRuleViolationException("File id is not a valid UUID: " + value);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
