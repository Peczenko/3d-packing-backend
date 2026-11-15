package com.packing.backend.infra.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SasWindowTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void everyRequestInsideOneWindowGetsTheSameStartAndExpirySoTheUrlStaysCacheable() {
        SasWindow early = SasWindow.aligned(Instant.parse("2026-11-06T10:00:00Z"),
                                            TEN_MINUTES);
        SasWindow late = SasWindow.aligned(Instant.parse("2026-11-06T10:09:59Z"),
                                           TEN_MINUTES);

        assertThat(late).isEqualTo(early);
        assertThat(early.start()).isEqualTo(Instant.parse("2026-11-06T09:55:00Z"));
        assertThat(early.expiry()).isEqualTo(Instant.parse("2026-11-06T10:20:00Z"));
    }

    @Test
    void theNextWindowStartsAtItsBoundary() {
        SasWindow next = SasWindow.aligned(Instant.parse("2026-11-06T10:10:00Z"),
                                           TEN_MINUTES);

        assertThat(next.expiry()).isEqualTo(Instant.parse("2026-11-06T10:30:00Z"));
    }

    @Test
    void aUrlAlwaysOutlivesTheRequestedValidity() {
        Instant lastMoment = Instant.parse("2026-11-06T10:09:59.999Z");

        SasWindow window = SasWindow.aligned(lastMoment, TEN_MINUTES);

        assertThat(Duration.between(lastMoment, window.expiry())).isGreaterThan(TEN_MINUTES);
        assertThat(window.start()).isBefore(lastMoment);
    }

    @Test
    void aSlidingWindowStartsBeforeNowToAbsorbClockSkewAndEndsExactlyOneValidityLater() {
        Instant now = Instant.parse("2026-11-06T10:03:17Z");

        SasWindow window = SasWindow.sliding(now, TEN_MINUTES);

        assertThat(window.start()).isEqualTo(Instant.parse("2026-11-06T09:58:17Z"));
        assertThat(window.expiry()).isEqualTo(Instant.parse("2026-11-06T10:13:17Z"));
    }
}
