package com.packing.backend.infra.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewSasWindowTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void everyRequestInsideOneWindowGetsTheSameStartAndExpirySoTheUrlStaysCacheable() {
        AzurePackingJobArtifactStore.SasWindow early = AzurePackingJobArtifactStore.SasWindow.containing(Instant.parse("2026-11-06T10:00:00Z"),
                                                                                                         TEN_MINUTES);
        AzurePackingJobArtifactStore.SasWindow late = AzurePackingJobArtifactStore.SasWindow.containing(Instant.parse("2026-11-06T10:09:59Z"),
                                                                                                        TEN_MINUTES);

        assertThat(late).isEqualTo(early);
        assertThat(early.start()).isEqualTo(Instant.parse("2026-11-06T09:55:00Z"));
        assertThat(early.expiry()).isEqualTo(Instant.parse("2026-11-06T10:20:00Z"));
    }

    @Test
    void theNextWindowStartsAtItsBoundary() {
        AzurePackingJobArtifactStore.SasWindow next = AzurePackingJobArtifactStore.SasWindow.containing(Instant.parse("2026-11-06T10:10:00Z"),
                                                                                                        TEN_MINUTES);

        assertThat(next.expiry()).isEqualTo(Instant.parse("2026-11-06T10:30:00Z"));
    }

    @Test
    void aUrlAlwaysOutlivesTheRequestedValidity() {
        Instant lastMoment = Instant.parse("2026-11-06T10:09:59.999Z");

        AzurePackingJobArtifactStore.SasWindow window = AzurePackingJobArtifactStore.SasWindow.containing(lastMoment, TEN_MINUTES);

        assertThat(Duration.between(lastMoment, window.expiry())).isGreaterThan(TEN_MINUTES);
        assertThat(window.start()).isBefore(lastMoment);
    }
}
