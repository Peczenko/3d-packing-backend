package com.packing.backend.infra.storage;

import java.time.Duration;
import java.time.Instant;

record SasWindow(Instant start, Instant expiry) {

    static final Duration CLOCK_SKEW_ALLOWANCE = Duration.ofMinutes(5);

    static SasWindow sliding(Instant now, Duration validity) {
        return new SasWindow(now.minus(CLOCK_SKEW_ALLOWANCE), now.plus(validity));
    }

    static SasWindow aligned(Instant now, Duration validity) {
        long seconds = Math.max(1, validity.toSeconds());
        long epoch = now.getEpochSecond();
        Instant windowStart = Instant.ofEpochSecond(epoch - Math.floorMod(epoch, seconds));
        return new SasWindow(windowStart.minus(CLOCK_SKEW_ALLOWANCE), windowStart.plusSeconds(2 * seconds));
    }
}
