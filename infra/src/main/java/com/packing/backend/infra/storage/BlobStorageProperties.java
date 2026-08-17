package com.packing.backend.infra.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.storage")
public record BlobStorageProperties(
        @NotBlank @DefaultValue("models") String containerName,
        @DefaultValue("5m") Duration downloadUrlTtl,
        @DefaultValue("ACCOUNT_KEY") SasMode sasMode,
        @DefaultValue("true") boolean autoCreateContainer,
        @DefaultValue("true") boolean enabled) {

    public enum SasMode {

        ACCOUNT_KEY,
        USER_DELEGATION
    }
}
