package com.packing.backend.infra.persistence.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TimestampsTest {

    @Test
    void normalisesToUtcOnTheWayOut() {
        Instant instant = Instant.parse("2026-07-28T10:15:30Z");

        assertThat(Timestamps.toOffsetDateTime(instant).getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void roundTripsAnInstantUnchanged() {
        Instant instant = Instant.parse("2026-07-28T10:15:30.123456Z");

        assertThat(Timestamps.toInstant(Timestamps.toOffsetDateTime(instant))).isEqualTo(instant);
    }

    @Test
    void keepsTheInstantWhenTheOffsetIsNotUtc() {
        OffsetDateTime nonUtc = OffsetDateTime.parse("2026-07-28T12:15:30+02:00");

        assertThat(Timestamps.toInstant(nonUtc)).isEqualTo(Instant.parse("2026-07-28T10:15:30Z"));
    }

    @Test
    void tolerateNullBecauseDeletedAtAndLastLoginAtAreRoutinelyNull() {
        assertThat(Timestamps.toOffsetDateTime(null)).isNull();
        assertThat(Timestamps.toInstant(null)).isNull();
    }
}
