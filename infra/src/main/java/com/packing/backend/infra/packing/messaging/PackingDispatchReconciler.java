package com.packing.backend.infra.packing.messaging;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.domain.packing.PackingJobId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

@RequiredArgsConstructor
@Slf4j
class PackingDispatchReconciler {

    private static final int BATCH_SIZE = 100;

    private final PackingJobFinder          finder;
    private final PackingJobDispatchService dispatcher;
    private final PackingJobArtifactStore   artifacts;
    private final PackingJobRecoveryService recovery;

    @EventListener(ApplicationReadyEvent.class)
    void reconcileOnStartup() {
        reconcileBatch();
    }

    @Scheduled(fixedDelayString = "${packing.messaging.reconcile-delay:PT30S}")
    void reconcilePeriodically() {
        reconcileBatch();
    }

    private void reconcileBatch() {
        finder.findUndispatched(BATCH_SIZE)
              .forEach(this::dispatchIndependently);
        finder.findRunning(BATCH_SIZE)
              .forEach(this::recoverIndependently);
    }

    private void dispatchIndependently(PackingJobId jobId) {
        try {
            dispatcher.dispatch(jobId);
        } catch (RuntimeException failure) {
            log.warn("Packing dispatch reconciliation deferred for {}", jobId, failure);
        }
    }

    private void recoverIndependently(PackingJobId jobId) {
        try {
            artifacts.findResult(jobId)
                     .ifPresent(result -> recovery.recoverResult(jobId, result));
        } catch (RuntimeException failure) {
            log.warn("Packing result reconciliation deferred for {}", jobId, failure);
        }
    }
}
