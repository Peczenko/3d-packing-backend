package com.packing.backend.infra.notification;

import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.alerts")
public record AlertProperties(
        @DefaultValue List<String> recipients,
        @DurationMin(seconds = 1) @DefaultValue("15m") Duration cooldown,
        @Positive @DefaultValue("20") int maxPerHour) {

    public AlertProperties {
        recipients = recipients == null ? List.of() : recipients.stream()
                .map(String::strip)
                .filter(recipient -> !recipient.isEmpty())
                .toList();
    }

    public boolean alertingEnabled() {
        return !recipients.isEmpty();
    }
}
