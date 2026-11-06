package com.packing.backend.infra.packing.preview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.preview")
public record PreviewProperties(
        @NotBlank @DefaultValue("gltfpack") String gltfpackPath,
        @NotNull @DefaultValue("5m") Duration gltfpackTimeout) {
}
