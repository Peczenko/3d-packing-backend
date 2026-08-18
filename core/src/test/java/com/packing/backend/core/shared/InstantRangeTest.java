package com.packing.backend.core.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InstantRangeTest {

    private static final Instant FROM = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void acceptsOpenAndOrderedRanges() {
        assertThat(new InstantRange(null, null)).isEqualTo(new InstantRange(null, null));
        assertThat(new InstantRange(FROM, null).from()).isEqualTo(FROM);
        assertThat(new InstantRange(null, BEFORE).before()).isEqualTo(BEFORE);
        assertThat(new InstantRange(FROM, BEFORE)).isEqualTo(new InstantRange(FROM, BEFORE));
    }

    @Test
    void rejectsEqualOrReversedBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstantRange(FROM, FROM))
                .withMessage("from must be before before");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstantRange(BEFORE, FROM))
                .withMessage("from must be before before");
    }
}
