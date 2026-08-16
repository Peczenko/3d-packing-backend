package com.packing.backend.infra.packing.messaging;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.infra.packing.PackingContractCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
class PackingDeadLetterReconciler {

    private static final int      BATCH_SIZE         = 20;
    private static final Duration RECEIVE_WAIT       = Duration.ofSeconds(1);
    private static final String   DELIVERY_EXHAUSTED = "MaxDeliveryCountExceeded";

    private final ServiceBusReceiverClient  dispatchReceiver;
    private final ServiceBusReceiverClient  resultReceiver;
    private final PackingContractCodec      codec;
    private final PackingJobArtifactStore   artifacts;
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
        reconcileQueue(resultReceiver, this::replayResult);
        reconcileQueue(dispatchReceiver, this::replayDispatch);
    }

    private void reconcileQueue(ServiceBusReceiverClient receiver, Replay replay) {
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
                                     Replay replay) {
        try {
            if (replay.settles(message)) {
                receiver.complete(message);
            }
        } catch (DomainRuleViolationException malformed) {
            log.warn("Packing dead-letter message {} is malformed and was left for inspection",
                     message.getMessageId(),
                     malformed);
        } catch (RuntimeException failure) {
            log.warn("Packing dead-letter message {} replay failed", message.getMessageId(), failure);
            abandon(receiver, message);
        }
    }

    private boolean replayDispatch(ServiceBusReceivedMessage message) {
        PackingJobId jobId = codec.decodeDispatch(message.getBody()
                                                         .toString())
                                  .jobId();
        Optional<PackingJobArtifactStore.ResultArtifact> stored = artifacts.findResult(jobId);
        if (stored.isPresent()) {
            recovery.recoverStalledResult(jobId, stored.get());
            return true;
        }
        if (recovery.resolveStalledDispatch(jobId, stallOf(message))) {
            return true;
        }
        log.info("Packing dispatch dead-letter for job {} kept: the job is still running and its "
                + "worker may still hold the message", jobId);
        return false;
    }

    private PackingJobRecoveryService.DispatchStall stallOf(ServiceBusReceivedMessage message) {
        return DELIVERY_EXHAUSTED.equalsIgnoreCase(message.getDeadLetterReason())
                                                                                  ? PackingJobRecoveryService.DispatchStall.DELIVERY_EXHAUSTED
                                                                                  : PackingJobRecoveryService.DispatchStall.EXPIRED;
    }

    private boolean replayResult(ServiceBusReceivedMessage message) {
        PackingWorkerEvent event = codec.decodeWorkerEvent(message.getBody()
                                                                  .toString());
        requireMatchingSession(message, event);
        workerEvents.apply(event);
        return true;
    }

    @FunctionalInterface
    private interface Replay {

        boolean settles(ServiceBusReceivedMessage message);
    }

    private void requireMatchingSession(ServiceBusReceivedMessage message, PackingWorkerEvent event) {
        if (!event.jobId()
                  .toString()
                  .equals(message.getSessionId())) {
            throw new DomainRuleViolationException("Packing result message session does not match job id");
        }
    }

    private void abandon(ServiceBusReceiverClient receiver, ServiceBusReceivedMessage message) {
        try {
            receiver.abandon(message);
        } catch (RuntimeException failure) {
            log.warn("Packing dead-letter message {} could not be abandoned", message.getMessageId(), failure);
        }
    }
}
