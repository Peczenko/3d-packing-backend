package com.packing.backend.infra.packing.messaging;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.infra.packing.PackingContractCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
class PackingDeadLetterReconciler {

    private static final int BATCH_SIZE = 20;
    private static final Duration RECEIVE_WAIT = Duration.ofSeconds(1);

    private final ServiceBusReceiverClient dispatchReceiver;
    private final ServiceBusReceiverClient resultReceiver;
    private final PackingContractCodec codec;
    private final PackingJobRecoveryService recovery;
    private final PackingWorkerEventService workerEvents;

    @EventListener(ApplicationReadyEvent.class)
    void reconcileOnStartup() {
        reconcileBatch();
    }

    @Scheduled(fixedDelayString = "PT1M")
    void reconcilePeriodically() {
        reconcileBatch();
    }

    private void reconcileBatch() {
        reconcileQueue(dispatchReceiver, this::replayDispatch);
        reconcileQueue(resultReceiver, this::replayResult);
    }

    private void reconcileQueue(ServiceBusReceiverClient receiver, Consumer<ServiceBusReceivedMessage> replay) {
        try {
            for (ServiceBusReceivedMessage message : receiver.receiveMessages(BATCH_SIZE, RECEIVE_WAIT)) {
                replayIndependently(receiver, message, replay);
            }
        } catch (RuntimeException failure) {
            log.warn("Packing dead-letter reconciliation deferred", failure);
        }
    }

    private void replayIndependently(ServiceBusReceiverClient receiver,
                                     ServiceBusReceivedMessage message,
                                     Consumer<ServiceBusReceivedMessage> replay) {
        try {
            replay.accept(message);
            receiver.complete(message);
        } catch (DomainRuleViolationException malformed) {
            log.warn("Packing dead-letter message {} is malformed and was left for inspection",
                    message.getMessageId(), malformed);
        } catch (RuntimeException failure) {
            log.warn("Packing dead-letter message {} replay failed", message.getMessageId(), failure);
            abandon(receiver, message);
        }
    }

    private void replayDispatch(ServiceBusReceivedMessage message) {
        recovery.failExhaustedDispatch(codec.decodeDispatch(message.getBody().toString()).jobId());
    }

    private void replayResult(ServiceBusReceivedMessage message) {
        workerEvents.apply(codec.decodeWorkerEvent(message.getBody().toString()));
    }

    private void abandon(ServiceBusReceiverClient receiver, ServiceBusReceivedMessage message) {
        try {
            receiver.abandon(message);
        } catch (RuntimeException failure) {
            log.warn("Packing dead-letter message {} could not be abandoned", message.getMessageId(), failure);
        }
    }
}
