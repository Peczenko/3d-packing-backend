package com.packing.backend.core.shared;

import java.time.Instant;

public record InstantRange(Instant from, Instant before) {

    public InstantRange {
        if (from != null && before != null && !from.isBefore(before)) {
            throw new IllegalArgumentException("from must be before before");
        }
    }
}
