package com.packing.backend.infra.packing.messaging;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

@Validated
@ConfigurationProperties("packing.messaging")
public record PackingMessagingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String fullyQualifiedNamespace,
        @DefaultValue("") String connectionString,
        @NotBlank @DefaultValue("packing-dispatch") String dispatchQueue,
        @NotBlank @DefaultValue("packing-results") String resultQueue,
        @NotNull @DurationMin(millis = 1) @DefaultValue("PT30S") Duration reconcileDelay,
        @Positive @DefaultValue("5") int resultConcurrentSessions) {

    @AssertTrue(message = "A Service Bus connection string or fully qualified namespace is required when messaging is enabled")
    public boolean hasConnectionConfiguration() {
        return !enabled || StringUtils.hasText(connectionString) || StringUtils.hasText(fullyQualifiedNamespace);
    }
}
