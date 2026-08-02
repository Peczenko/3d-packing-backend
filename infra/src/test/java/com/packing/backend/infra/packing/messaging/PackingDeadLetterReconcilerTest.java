package com.packing.backend.infra.packing.messaging;

import com.azure.core.util.BinaryData;
import com.azure.core.util.IterableStream;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.PackingContractCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingDeadLetterReconcilerTest {

    private static final String CHECKSUM = "a".repeat(64);

    @Mock
    private ServiceBusReceiverClient dispatchReceiver;
    @Mock
    private ServiceBusReceiverClient resultReceiver;
    @Mock
    private PackingJobRecoveryService recovery;
    @Mock
    private PackingWorkerEventService workerEvents;
    @Mock
    private ServiceBusReceivedMessage dispatchMessage;
    @Mock
    private ServiceBusReceivedMessage resultMessage;

    private final PackingContractCodec codec = new PackingContractCodec(new ObjectMapper());
    private PackingDeadLetterReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new PackingDeadLetterReconciler(dispatchReceiver, resultReceiver, codec, recovery, workerEvents);
        when(dispatchReceiver.receiveMessages(20, Duration.ofSeconds(1))).thenReturn(IterableStream.of(List.of()));
        when(resultReceiver.receiveMessages(20, Duration.ofSeconds(1))).thenReturn(IterableStream.of(List.of()));
    }

    @Test
    void replaysDispatchAndResultDeadLettersBeforeCompletingEachMessage() {
        PackingJobId dispatchId = PackingJobId.generate();
        PackingWorkerEvent event = new PackingWorkerEvent.Started(1, PackingJobId.generate(), "packer 0.1.0", CHECKSUM);
        received(dispatchMessage, codec.encodeDispatch(PackingDispatchMessage.versionOne(dispatchId)));
        received(resultMessage, codec.encodeWorkerEvent(event));
        when(resultMessage.getSessionId()).thenReturn(event.jobId().toString());
        when(dispatchReceiver.receiveMessages(20, Duration.ofSeconds(1))).thenReturn(IterableStream.of(List.of(dispatchMessage)));
        when(resultReceiver.receiveMessages(20, Duration.ofSeconds(1))).thenReturn(IterableStream.of(List.of(resultMessage)));

        reconciler.reconcilePeriodically();

        InOrder order = inOrder(recovery, workerEvents, dispatchReceiver, resultReceiver);
        order.verify(recovery).failExhaustedDispatch(dispatchId);
        order.verify(dispatchReceiver).complete(dispatchMessage);
        order.verify(workerEvents).apply(event);
        order.verify(resultReceiver).complete(resultMessage);
    }

    @Test
    void leavesAResultWithAMismatchedSessionUnsettledWithoutApplyingIt() {
        PackingWorkerEvent event = new PackingWorkerEvent.Started(1, PackingJobId.generate(), "packer 0.1.0", CHECKSUM);
        received(resultMessage, codec.encodeWorkerEvent(event));
        when(resultMessage.getSessionId()).thenReturn(PackingJobId.generate().toString());
        when(resultMessage.getMessageId()).thenReturn("result-session-mismatch");
        when(resultReceiver.receiveMessages(20, Duration.ofSeconds(1)))
                .thenReturn(IterableStream.of(List.of(resultMessage)));

        reconciler.reconcilePeriodically();

        verifyNoInteractions(workerEvents);
        verify(resultReceiver, never()).complete(resultMessage);
        verify(resultReceiver, never()).abandon(resultMessage);
    }

    @Test
    void abandonsOnlyTheTransientFailureAndContinuesTheRemainingMessage() {
        PackingJobId failedId = PackingJobId.generate();
        PackingJobId nextId = PackingJobId.generate();
        ServiceBusReceivedMessage nextMessage = org.mockito.Mockito.mock(ServiceBusReceivedMessage.class);
        received(dispatchMessage, codec.encodeDispatch(PackingDispatchMessage.versionOne(failedId)));
        when(dispatchMessage.getMessageId()).thenReturn("dispatch-1");
        received(nextMessage, codec.encodeDispatch(PackingDispatchMessage.versionOne(nextId)));
        when(dispatchReceiver.receiveMessages(20, Duration.ofSeconds(1)))
                .thenReturn(IterableStream.of(List.of(dispatchMessage, nextMessage)));
        doThrow(new IllegalStateException("database unavailable")).when(recovery).failExhaustedDispatch(failedId);

        reconciler.reconcilePeriodically();

        verify(dispatchReceiver).abandon(dispatchMessage);
        verify(dispatchReceiver, never()).complete(dispatchMessage);
        verify(recovery).failExhaustedDispatch(nextId);
        verify(dispatchReceiver).complete(nextMessage);
    }

    @Test
    void leavesMalformedEvidenceInTheDeadLetterQueueAndContinuesOtherQueues() {
        received(dispatchMessage, "not json");
        when(dispatchMessage.getMessageId()).thenReturn("dispatch-malformed");
        PackingWorkerEvent event = new PackingWorkerEvent.Started(1, PackingJobId.generate(), "packer 0.1.0", CHECKSUM);
        received(resultMessage, codec.encodeWorkerEvent(event));
        when(resultMessage.getSessionId()).thenReturn(event.jobId().toString());
        when(dispatchReceiver.receiveMessages(20, Duration.ofSeconds(1))).thenReturn(IterableStream.of(List.of(dispatchMessage)));
        when(resultReceiver.receiveMessages(20, Duration.ofSeconds(1))).thenReturn(IterableStream.of(List.of(resultMessage)));

        reconciler.reconcilePeriodically();

        verifyNoInteractions(recovery);
        verify(dispatchReceiver, never()).complete(dispatchMessage);
        verify(dispatchReceiver, never()).abandon(dispatchMessage);
        verify(workerEvents).apply(event);
        verify(resultReceiver).complete(resultMessage);
    }

    @Test
    void pollsEachDeadLetterQueueAtStartupAndEveryMinute() throws NoSuchMethodException {
        reconciler.reconcileOnStartup();

        Method startup = PackingDeadLetterReconciler.class.getDeclaredMethod("reconcileOnStartup");
        assertThat(startup.getAnnotation(EventListener.class).value()).containsExactly(ApplicationReadyEvent.class);
        Method scheduled = PackingDeadLetterReconciler.class.getDeclaredMethod("reconcilePeriodically");
        assertThat(scheduled.getAnnotation(Scheduled.class).fixedDelayString()).isEqualTo("PT1M");
        verify(dispatchReceiver).receiveMessages(20, Duration.ofSeconds(1));
        verify(resultReceiver).receiveMessages(20, Duration.ofSeconds(1));
    }

    private static void received(ServiceBusReceivedMessage message, String body) {
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
    }
}
