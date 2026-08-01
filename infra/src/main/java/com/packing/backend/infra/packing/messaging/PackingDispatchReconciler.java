package com.packing.backend.infra.packing.messaging;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.domain.packing.PackingJobId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(PackingDispatchSender.class)
@RequiredArgsConstructor
@Slf4j
class PackingDispatchReconciler {

    private static final int BATCH_SIZE = 100;

    private final PackingJobFinder finder;
    private final PackingJobDispatchService dispatcher;

    @EventListener(ApplicationReadyEvent.class)
    void reconcileOnStartup() {
        reconcileBatch();
    }

    @Scheduled(fixedDelayString = "${packing.messaging.reconcile-delay:PT30S}")
    void reconcilePeriodically() {
        reconcileBatch();
    }

    private void reconcileBatch() {
        finder.findUndispatched(BATCH_SIZE).forEach(this::dispatchIndependently);
    }

    private void dispatchIndependently(PackingJobId jobId) {
        try {
            dispatcher.dispatch(jobId);
        } catch (RuntimeException failure) {
            log.warn("Packing dispatch reconciliation deferred for {}", jobId, failure);
        }
    }
}
