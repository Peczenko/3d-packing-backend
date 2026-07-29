package com.packing.backend.core.shared;

import com.packing.backend.domain.shared.DomainRuleViolationException;

public record PageRequest(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageRequest {
        if (page < 0) {
            throw new DomainRuleViolationException("Page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new DomainRuleViolationException(
                    "Page size must be between 1 and " + MAX_SIZE);
        }
    }

    /** Widened deliberately: page * size overflows int at a large page index. */
    public long offset() {
        return (long) page * size;
    }
}
