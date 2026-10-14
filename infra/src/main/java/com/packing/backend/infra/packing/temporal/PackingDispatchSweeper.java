package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.domain.packing.PackingJobId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

@RequiredArgsConstructor
@Slf4j
class PackingDispatchSweeper {

    static final int BATCH_SIZE = 100;

    private final PackingJobFinder          finder;
    private final PackingJobDispatchService dispatcher;

    @EventListener(ApplicationReadyEvent.class)
    void sweepOnStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${packing.dispatch.sweep-delay:PT30S}")
    void sweepPeriodically() {
        sweep();
    }

    private void sweep() {
        finder.findUndispatched(BATCH_SIZE)
              .forEach(this::dispatchIndependently);
    }

    private void dispatchIndependently(PackingJobId jobId) {
        try {
            dispatcher.dispatch(jobId);
        } catch (RuntimeException failure) {
            log.warn("Packing dispatch deferred for {}", jobId, failure);
        }
    }
}
