package com.packing.backend.infra.packing.temporal;

import io.temporal.worker.WorkerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

@RequiredArgsConstructor
@Slf4j
class TemporalWorkerStarter {

    private final WorkerFactory workers;

    @EventListener(ApplicationReadyEvent.class)
    void startWhenReady() {
        tryStart();
    }

    @Scheduled(fixedDelayString = "PT5S")
    void retryUntilStarted() {
        tryStart();
    }

    private void tryStart() {
        if (workers.isStarted()) {
            return;
        }
        try {
            workers.start();
            log.info("Temporal workers started");
        } catch (RuntimeException unavailable) {
            log.warn("Temporal workers not started yet, retrying: {}", unavailable.getMessage());
        }
    }
}
