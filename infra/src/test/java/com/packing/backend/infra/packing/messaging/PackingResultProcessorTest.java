package com.packing.backend.infra.packing.messaging;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.PackingContractCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingResultProcessorTest {

    private static final String CHECKSUM = "a".repeat(64);

    @Mock
    private ServiceBusClientBuilder.ServiceBusSessionProcessorClientBuilder builder;
    @Mock
    private ServiceBusProcessorClient                                       client;
    @Mock
    private PackingWorkerEventService                                       workerEvents;
    @Mock
    private ServiceBusReceivedMessageContext                                context;
    @Mock
    private ServiceBusReceivedMessage                                       message;

    private final PackingContractCodec codec = new PackingContractCodec(new ObjectMapper());

    private Consumer<ServiceBusReceivedMessageContext> processMessage;
    private Consumer<ServiceBusErrorContext>           processError;
    private PackingResultProcessor                     processor;

    @BeforeEach
    void setUp() {
        when(builder.processMessage(any())).thenReturn(builder);
        when(builder.processError(any())).thenReturn(builder);
        when(builder.buildProcessorClient()).thenReturn(client);

        processor = new PackingResultProcessor(builder, codec, workerEvents);

        ArgumentCaptor<Consumer<ServiceBusReceivedMessageContext>> messageCallback = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<ServiceBusErrorContext>> errorCallback = ArgumentCaptor.forClass(Consumer.class);
        verify(builder).processMessage(messageCallback.capture());
        verify(builder).processError(errorCallback.capture());
        processMessage = messageCallback.getValue();
        processError = errorCallback.getValue();
    }

    @Test
    void buildsOnceWithCallbacksRegisteredBeforeBuildingThenStartsAndClosesTheClient() {
        InOrder order = inOrder(builder);
        order.verify(builder)
             .processMessage(any());
        order.verify(builder)
             .processError(any());
        order.verify(builder)
             .buildProcessorClient();
        verify(builder).buildProcessorClient();

        processor.start();
        processor.close();

        InOrder lifecycle = inOrder(client);
        lifecycle.verify(client)
                 .start();
        lifecycle.verify(client)
                 .close();
    }

    @Test
    void validEventIsAppliedBeforeItsMessageIsCompleted() {
        PackingWorkerEvent event = started();
        received(event,
                 event.jobId()
                      .toString());

        processMessage.accept(context);

        InOrder order = inOrder(workerEvents, context);
        order.verify(workerEvents)
             .apply(event);
        order.verify(context)
             .complete();
        verify(context, never()).abandon();
    }

    @Test
    void mismatchedSessionAbandonsWithoutApplyingOrCompleting() {
        PackingWorkerEvent event = started();
        received(event,
                 PackingJobId.generate()
                             .toString());

        processMessage.accept(context);

        verifyNoInteractions(workerEvents);
        verify(context).abandon();
        verify(context, never()).complete();
    }

    @Test
    void decodeFailureAbandonsWithoutApplyingOrCompleting() {
        when(context.getMessage()).thenReturn(message);
        when(message.getBody()).thenReturn(BinaryData.fromString("not json"));

        processMessage.accept(context);

        verifyNoInteractions(workerEvents);
        verify(context).abandon();
        verify(context, never()).complete();
    }

    @Test
    void serviceFailureAbandonsWithoutCompleting() {
        PackingWorkerEvent event = started();
        received(event,
                 event.jobId()
                      .toString());
        doThrow(new IllegalStateException("database unavailable")).when(workerEvents)
                                                                  .apply(event);

        processMessage.accept(context);

        verify(workerEvents).apply(event);
        verify(context).abandon();
        verify(context, never()).complete();
    }

    @Test
    void brokerErrorsAreLoggedWithoutSettlingAMessage() {
        ServiceBusErrorContext error = org.mockito.Mockito.mock(ServiceBusErrorContext.class);

        processError.accept(error);

        verifyNoInteractions(context, workerEvents);
    }

    private void received(PackingWorkerEvent event, String sessionId) {
        when(context.getMessage()).thenReturn(message);
        when(message.getBody()).thenReturn(BinaryData.fromString(codec.encodeWorkerEvent(event)));
        when(message.getSessionId()).thenReturn(sessionId);
    }

    private static PackingWorkerEvent.Started started() {
        return new PackingWorkerEvent.Started(1, PackingJobId.generate(), "packer 0.1.0", CHECKSUM);
    }
}
