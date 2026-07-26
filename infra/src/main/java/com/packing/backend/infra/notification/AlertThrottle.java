package com.packing.backend.infra.notification;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
class AlertThrottle {

    private static final Duration BUDGET_WINDOW = Duration.ofHours(1);

    private static final int MAX_TRACKED = 500;

    private final Clock clock;
    private final Duration cooldown;
    private final int maxPerHour;
    private final Map<String, Occurrence> occurrences = new HashMap<>();

    private Instant budgetWindowStart;
    private int sentInWindow;
    private boolean budgetExhaustionLogged;
    private Instant lastEvictionAt;

    AlertThrottle(Clock clock, Duration cooldown, int maxPerHour) {
        this.clock = clock;
        this.cooldown = cooldown;
        this.maxPerHour = maxPerHour;
    }

    synchronized Decision evaluate(String fingerprint) {
        Instant now = clock.instant();
        evictStale(now);

        Occurrence occurrence = occurrences.computeIfAbsent(fingerprint, key -> new Occurrence(now));
        occurrence.lastSeenAt = now;

        if (occurrence.lastAlertAt != null && now.isBefore(occurrence.lastAlertAt.plus(cooldown))) {
            occurrence.suppressed++;
            return Decision.suppressed();
        }
        if (!consumeBudget(now)) {
            occurrence.suppressed++;

            if (!budgetExhaustionLogged) {
                budgetExhaustionLogged = true;
                log.warn("Alert budget of {} per hour is exhausted; suppressing further alerts "
                        + "until the window resets. Check the logs directly.", maxPerHour);
            }
            return Decision.suppressed();
        }

        Decision decision = new Decision(true, occurrence.suppressed, occurrence.lastAlertAt);
        occurrence.suppressed = 0;
        occurrence.lastAlertAt = now;
        return decision;
    }

    private boolean consumeBudget(Instant now) {
        if (budgetWindowStart == null || !now.isBefore(budgetWindowStart.plus(BUDGET_WINDOW))) {
            budgetWindowStart = now;
            sentInWindow = 0;
            budgetExhaustionLogged = false;
        }
        if (sentInWindow >= maxPerHour) {
            return false;
        }
        sentInWindow++;
        return true;
    }

    private void evictStale(Instant now) {
        boolean due = lastEvictionAt == null || !now.isBefore(lastEvictionAt.plus(cooldown));
        if (!due && occurrences.size() < MAX_TRACKED) {
            return;
        }
        lastEvictionAt = now;

        Instant cutoff = now.minus(cooldown.multipliedBy(2));
        occurrences.values().removeIf(occurrence -> occurrence.lastSeenAt.isBefore(cutoff));
        if (occurrences.size() >= MAX_TRACKED) {
            log.warn("Tracking {} distinct failures; clearing throttle state", occurrences.size());
            occurrences.clear();
        }
    }

    record Decision(boolean send, long suppressedSinceLastAlert, Instant lastAlertAt) {

        static Decision suppressed() {
            return new Decision(false, 0, null);
        }
    }

    private static final class Occurrence {

        private Instant lastSeenAt;
        private Instant lastAlertAt;
        private long suppressed;

        private Occurrence(Instant firstSeenAt) {
            this.lastSeenAt = firstSeenAt;
        }
    }
}
