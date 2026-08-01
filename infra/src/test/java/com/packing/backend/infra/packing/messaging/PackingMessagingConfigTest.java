package com.packing.backend.infra.packing.messaging;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.infra.packing.PackingContractCodec;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PackingMessagingConfigTest {

    @Test
    void disabledMessagingCreatesNoBrokerClientsSchedulingOrDispatchComponents() {
        runner(defaultProperties()).withPropertyValues("packing.messaging.enabled=false")
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
        runner(properties(true, connectionString(), Duration.ofSeconds(30))).withPropertyValues("packing.messaging.enabled=true")
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
        assertThat(bind(Map.of("packing.messaging.enabled", "false"))).isEqualTo(defaultProperties());
    }

    @Test
    void bindsTheConfiguredLocalConnectionString() {
        assertThat(bind(Map.of("packing.messaging.connection-string", connectionString())).connectionString())
                .isEqualTo(connectionString());
    }

    @Test
    void rejectsAZeroReconcileDelay() {
        assertThat(validator().validate(properties(false, "", Duration.ZERO))).isNotEmpty();
    }

    @Test
    void rejectsANegativeReconcileDelay() {
        assertThat(validator().validate(properties(false, "", Duration.ofSeconds(-1)))).isNotEmpty();
    }

    @Test
    void rejectsEnabledMessagingWithoutConnectionConfiguration() {
        assertThat(validator().validate(properties(true, "", Duration.ofSeconds(30)))).isNotEmpty();
    }

    private static ApplicationContextRunner runner(PackingMessagingProperties properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PackingMessagingConfig.class))
                .withBean(PackingMessagingProperties.class, () -> properties)
                .withBean(PackingContractCodec.class, () -> new PackingContractCodec(new ObjectMapper()))
                .withBean(PackingJobRepository.class, () -> mock(PackingJobRepository.class))
                .withBean(PackingJobArtifactStore.class, () -> mock(PackingJobArtifactStore.class))
                .withBean(PackingJobFinder.class, () -> mock(PackingJobFinder.class))
                .withBean(Clock.class, Clock::systemUTC);
    }

    private static PackingMessagingProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("packing.messaging", Bindable.of(PackingMessagingProperties.class))
                .orElseThrow(IllegalStateException::new);
    }

    private static PackingMessagingProperties defaultProperties() {
        return properties(false, "", Duration.ofSeconds(30));
    }

    private static PackingMessagingProperties properties(boolean enabled, String connectionString, Duration reconcileDelay) {
        return new PackingMessagingProperties(
                enabled, "", connectionString, "packing-dispatch", "packing-results", reconcileDelay, 5);
    }

    private static String connectionString() {
        return "Endpoint=sb://example.servicebus.windows.net/;SharedAccessKeyName=name;SharedAccessKey=key";
    }

    private static Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }
}
