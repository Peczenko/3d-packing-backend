package com.packing.backend.infra.notification;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank @DefaultValue("noreply@3dpacking.dev") String fromAddress,
        @NotBlank @DefaultValue("3D Packing") String fromName,
        @DefaultValue Brevo brevo) {

    public record Brevo(
            @NotBlank @DefaultValue("https://api.brevo.com") String baseUrl,
            String apiKey,
            @DurationMin(millis = 1) @DefaultValue("10s") Duration connectTimeout,
            @DurationMin(millis = 1) @DefaultValue("20s") Duration readTimeout) {
    }
}
