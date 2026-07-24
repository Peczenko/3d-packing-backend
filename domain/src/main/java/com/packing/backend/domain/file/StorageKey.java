package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

public record StorageKey(String value) {

    public static final String PREFIX = "files/";
    public static final int MAX_LENGTH = 512;

    public StorageKey {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException("Storage key must not be blank");
        }
        if (!value.startsWith(PREFIX)) {
            throw new DomainRuleViolationException("Storage key must start with '" + PREFIX + "'");
        }
        if (value.length() > MAX_LENGTH) {
            throw new DomainRuleViolationException(
                    "Storage key must be at most " + MAX_LENGTH + " characters");
        }
        if (value.contains("..")) {
            throw new DomainRuleViolationException("Storage key must not contain '..'");
        }
    }

    public static StorageKey forFile(FileId id) {
        return new StorageKey(PREFIX + id.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
