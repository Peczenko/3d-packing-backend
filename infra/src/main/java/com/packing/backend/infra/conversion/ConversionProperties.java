package com.packing.backend.infra.conversion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.conversion")
public record ConversionProperties(
        @NotBlank @DefaultValue("assimp") String assimpPath,
        @NotNull @DefaultValue("2m") Duration timeout,
        @Positive @DefaultValue("1") int maxConcurrent) {
}
