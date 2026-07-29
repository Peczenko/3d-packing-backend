package com.packing.backend.infra.persistence.shared;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The columns are {@code timestamp with time zone}, which jOOQ surfaces as
 * {@link OffsetDateTime}, while the domain speaks {@link Instant} — an instant has no offset
 * to get wrong. Everything is normalised to UTC on the way out.
 */
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
