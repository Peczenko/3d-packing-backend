package com.packing.backend.infra.persistence.shared;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Timestamps {

    private Timestamps() {
    }

    public static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
