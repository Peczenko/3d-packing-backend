package com.packing.backend.infra.packing.messaging;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.domain.packing.event.PackingJobQueued;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnBean(PackingDispatchSender.class)
@RequiredArgsConstructor
@Slf4j
class PackingJobQueuedListener {

    private final PackingJobDispatchService dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(PackingJobQueued event) {
        try {
            dispatcher.dispatch(event.jobId());
        } catch (RuntimeException failure) {
            log.warn("Packing dispatch deferred for {}", event.jobId(), failure);
        }
    }
}
