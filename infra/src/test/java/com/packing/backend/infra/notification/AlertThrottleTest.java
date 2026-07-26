package com.packing.backend.infra.notification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AlertThrottleTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(15);
    private static final String FINGERPRINT = "500:java.lang.IllegalStateException:Svc.go:12:/api/v1/files";
    private static final String OTHER_FINGERPRINT = "500:java.io.IOException:Svc.read:44:/api/v1/users";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-26T10:00:00Z"));
    private final AlertThrottle throttle = new AlertThrottle(clock, COOLDOWN, 3);

    @Test
    void sendsTheFirstSightingOfAFailure() {
        AlertThrottle.Decision decision = throttle.evaluate(FINGERPRINT);

        assertThat(decision.send()).isTrue();
        assertThat(decision.suppressedSinceLastAlert()).isZero();
        assertThat(decision.lastAlertAt()).isNull();
    }

    @Test
    void suppressesRepeatsWithinTheCooldown() {
        throttle.evaluate(FINGERPRINT);

        clock.advance(Duration.ofMinutes(14));

        assertThat(throttle.evaluate(FINGERPRINT).send()).isFalse();
    }

    @Test
    void sendsAgainAfterTheCooldownAndReportsWhatWasSuppressed() {
        Instant firstAlertAt = clock.instant();
        throttle.evaluate(FINGERPRINT);
        clock.advance(Duration.ofMinutes(5));
        throttle.evaluate(FINGERPRINT);
        throttle.evaluate(FINGERPRINT);

        clock.advance(Duration.ofMinutes(11));
        AlertThrottle.Decision decision = throttle.evaluate(FINGERPRINT);

        assertThat(decision.send()).isTrue();
        assertThat(decision.suppressedSinceLastAlert()).isEqualTo(2);
        assertThat(decision.lastAlertAt()).isEqualTo(firstAlertAt);
    }

    @Test
    void resetsTheSuppressedCountOnceItHasBeenReported() {
        throttle.evaluate(FINGERPRINT);
        throttle.evaluate(FINGERPRINT);
        clock.advance(COOLDOWN.plusMinutes(1));
        throttle.evaluate(FINGERPRINT);

        clock.advance(COOLDOWN.plusMinutes(1));

        assertThat(throttle.evaluate(FINGERPRINT).suppressedSinceLastAlert()).isZero();
    }

    @Test
    void tracksDistinctFailuresIndependently() {
        throttle.evaluate(FINGERPRINT);

        assertThat(throttle.evaluate(OTHER_FINGERPRINT).send()).isTrue();
    }

    @Test
    void stopsSendingOnceTheHourlyBudgetIsSpent() {
        for (int i = 0; i < 3; i++) {
            assertThat(throttle.evaluate("failure-" + i).send()).isTrue();
        }

        assertThat(throttle.evaluate("failure-4").send()).isFalse();
    }

    @Test
    void resumesSendingInTheNextHourlyWindow() {
        for (int i = 0; i < 4; i++) {
            throttle.evaluate("failure-" + i);
        }

        clock.advance(Duration.ofHours(1));

        assertThat(throttle.evaluate("failure-5").send()).isTrue();
    }

    /**
     * A budget spent on suppressed duplicates would defeat the point of having both limits:
     * the cooldown must reject first, so the hourly ceiling only ever counts sent mail.
     */
    @Test
    void doesNotSpendBudgetOnFailuresTheCooldownAlreadyRejected() {
        throttle.evaluate(FINGERPRINT);
        for (int i = 0; i < 20; i++) {
            throttle.evaluate(FINGERPRINT);
        }

        assertThat(throttle.evaluate("failure-a").send()).isTrue();
        assertThat(throttle.evaluate("failure-b").send()).isTrue();
        assertThat(throttle.evaluate("failure-c").send()).isFalse();
    }

    @Test
    void forgetsAFailureThatHasNotRecurredForTwoCooldowns() {
        throttle.evaluate(FINGERPRINT);
        throttle.evaluate(FINGERPRINT);

        clock.advance(COOLDOWN.multipliedBy(2).plusMinutes(1));
        AlertThrottle.Decision decision = throttle.evaluate(FINGERPRINT);

        assertThat(decision.send()).isTrue();
        assertThat(decision.suppressedSinceLastAlert()).isZero();
        assertThat(decision.lastAlertAt()).isNull();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
