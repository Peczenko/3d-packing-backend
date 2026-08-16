package com.packing.backend.infra.packing.messaging;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.infra.packing.PackingContractCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "packing.messaging", name = "enabled", havingValue = "true")
class PackingResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(PackingResultProcessor.class);

    private final ServiceBusProcessorClient processor;
    private final PackingContractCodec codec;
    private final PackingWorkerEventService workerEvents;

    PackingResultProcessor(ServiceBusClientBuilder.ServiceBusSessionProcessorClientBuilder builder,
                           PackingContractCodec codec,
                           PackingWorkerEventService workerEvents) {
        this.codec = codec;
        this.workerEvents = workerEvents;
        this.processor = builder.processMessage(this::processMessage)
                .processError(this::processError)
                .buildProcessorClient();
    }

    @PostConstruct
    void start() {
        processor.start();
    }

    @PreDestroy
    void close() {
        processor.close();
    }

    private void processMessage(ServiceBusReceivedMessageContext context) {
        try {
            PackingWorkerEvent event = codec.decodeWorkerEvent(context.getMessage().getBody().toString());
            if (!event.jobId().toString().equals(context.getMessage().getSessionId())) {
                throw new IllegalArgumentException("Packing result message session does not match job id");
            }
            workerEvents.apply(event);
            context.complete();
        } catch (Exception failure) {
            log.warn("Packing result message processing failed", failure);
            context.abandon();
        }
    }

    private void processError(ServiceBusErrorContext context) {
        log.warn("Packing result processor error from {} on {}", context.getErrorSource(),
                context.getEntityPath(), context.getException());
    }
}
