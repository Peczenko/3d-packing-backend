package com.packing.backend.infra.packingmessaging;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.infra.packing.PackingContractCodec;
import com.packing.backend.infra.packing.messaging.PackingMessagingConfig;
import com.packing.backend.infra.packing.messaging.PackingMessagingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PackingMessagingConfigTest {

    @Test
    void disabledMessagingCreatesNoBrokerClientsSchedulingOrDispatchComponents() {
        runner().withPropertyValues("packing.messaging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ServiceBusSenderClient.class);
                    assertThat(context).doesNotHaveBean(ServiceBusReceiverClient.class);
                    assertThat(context).doesNotHaveBean(PackingDispatchSender.class);
                    assertThat(context).doesNotHaveBean(PackingJobDispatchService.class);
                    assertThat(context).doesNotHaveBean("packingJobQueuedListener");
                    assertThat(context).doesNotHaveBean("packingDispatchReconciler");
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    void enabledMessagingWiresTheClientsSchedulerAndDispatchComponents() {
        runner().withPropertyValues(
                        "packing.messaging.enabled=true",
                        "packing.messaging.connection-string=Endpoint=sb://example.servicebus.windows.net/;"
                                + "SharedAccessKeyName=name;SharedAccessKey=key")
                .run(context -> {
                    assertThat(context).hasBean("packingDispatchSenderClient");
                    assertThat(context).hasBean("packingResultSenderClient");
                    assertThat(context).hasBean("packingDispatchDeadLetterReceiver");
                    assertThat(context).hasBean("packingResultDeadLetterReceiver");
                    assertThat(context).hasBean("packingResultProcessorBuilder");
                    assertThat(context).hasSingleBean(PackingDispatchSender.class);
                    assertThat(context).hasSingleBean(PackingJobDispatchService.class);
                    assertThat(context).hasBean("packingJobQueuedListener");
                    assertThat(context).hasBean("packingDispatchReconciler");
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    void bindsTheSharedMessagingDefaults() {
        runner().run(context -> assertThat(context.getBean(PackingMessagingProperties.class))
                .isEqualTo(new PackingMessagingProperties(
                        false, "", "", "packing-dispatch", "packing-results", java.time.Duration.ofSeconds(30), 5)));
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PackingMessagingConfig.class, TestConfiguration.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PackingMessagingProperties.class)
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        PackingContractCodec packingContractCodec(ObjectMapper objectMapper) {
            return new PackingContractCodec(objectMapper);
        }

        @Bean
        PackingJobRepository packingJobRepository() {
            return mock(PackingJobRepository.class);
        }

        @Bean
        PackingJobArtifactStore packingJobArtifactStore() {
            return mock(PackingJobArtifactStore.class);
        }

        @Bean
        PackingJobFinder packingJobFinder() {
            return mock(PackingJobFinder.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
