package com.packing.backend.infra.packing.messaging;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.messaging.servicebus.models.SubQueue;
import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.infra.packing.PackingContractCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "packing.messaging", name = "enabled", havingValue = "true")
public class PackingMessagingConfig {

    @Bean
    ServiceBusSenderClient packingDispatchSenderClient(PackingMessagingProperties properties) {
        return clientBuilder(properties).sender()
                                        .queueName(properties.dispatchQueue())
                                        .buildClient();
    }

    @Bean
    ServiceBusSenderClient packingResultSenderClient(PackingMessagingProperties properties) {
        return clientBuilder(properties).sender()
                                        .queueName(properties.resultQueue())
                                        .buildClient();
    }

    @Bean
    ServiceBusReceiverClient packingDispatchDeadLetterReceiver(PackingMessagingProperties properties) {
        return deadLetterReceiver(properties, properties.dispatchQueue());
    }

    @Bean
    ServiceBusReceiverClient packingResultDeadLetterReceiver(PackingMessagingProperties properties) {
        return deadLetterReceiver(properties, properties.resultQueue());
    }

    @Bean
    ServiceBusClientBuilder.ServiceBusSessionProcessorClientBuilder packingResultProcessorBuilder(
                                                                                                  PackingMessagingProperties properties) {
        return configureResultProcessor(clientBuilder(properties).sessionProcessor(), properties);
    }

    static ServiceBusClientBuilder.ServiceBusSessionProcessorClientBuilder configureResultProcessor(
                                                                                                    ServiceBusClientBuilder.ServiceBusSessionProcessorClientBuilder builder,
                                                                                                    PackingMessagingProperties properties) {
        return builder
                      .queueName(properties.resultQueue())
                      .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                      .disableAutoComplete()
                      .maxConcurrentSessions(properties.resultConcurrentSessions())
                      .maxAutoLockRenewDuration(Duration.ofMinutes(5))
                      .maxConcurrentCalls(1);
    }

    @Bean
    PackingDispatchSender azurePackingDispatchSender(
                                                     @Qualifier("packingDispatchSenderClient") ServiceBusSenderClient sender,
                                                     PackingContractCodec codec) {
        return new AzurePackingDispatchSender(sender, codec);
    }

    @Bean
    PackingJobDispatchService packingJobDispatchService(PackingJobRepository jobs,
                                                        PackingJobArtifactStore artifacts,
                                                        PackingDispatchSender dispatch,
                                                        Clock clock) {
        return new PackingJobDispatchService(jobs, artifacts, dispatch, clock);
    }

    @Bean
    PackingJobQueuedListener packingJobQueuedListener(PackingJobDispatchService dispatcher) {
        return new PackingJobQueuedListener(dispatcher);
    }

    @Bean
    PackingDispatchReconciler packingDispatchReconciler(PackingJobFinder finder,
                                                        PackingJobDispatchService dispatcher,
                                                        PackingJobArtifactStore artifacts,
                                                        PackingJobRecoveryService recovery) {
        return new PackingDispatchReconciler(finder, dispatcher, artifacts, recovery);
    }

    @Bean
    PackingDeadLetterReconciler packingDeadLetterReconciler(
                                                            @Qualifier("packingDispatchDeadLetterReceiver") ServiceBusReceiverClient dispatchReceiver,
                                                            @Qualifier("packingResultDeadLetterReceiver") ServiceBusReceiverClient resultReceiver,
                                                            PackingContractCodec codec,
                                                            PackingJobArtifactStore artifacts,
                                                            PackingJobRecoveryService recovery,
                                                            PackingWorkerEventService workerEvents) {
        return new PackingDeadLetterReconciler(
                                               dispatchReceiver,
                                               resultReceiver,
                                               codec,
                                               artifacts,
                                               recovery,
                                               workerEvents);
    }

    private ServiceBusReceiverClient deadLetterReceiver(PackingMessagingProperties properties, String queueName) {
        return clientBuilder(properties).receiver()
                                        .queueName(queueName)
                                        .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                                        .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                                        .buildClient();
    }

    private ServiceBusClientBuilder clientBuilder(PackingMessagingProperties properties) {
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (StringUtils.hasText(properties.connectionString())) {
            builder.connectionString(properties.connectionString());
        } else {
            builder.fullyQualifiedNamespace(properties.fullyQualifiedNamespace())
                   .credential(new DefaultAzureCredentialBuilder().build());
        }
        return builder;
    }
}
