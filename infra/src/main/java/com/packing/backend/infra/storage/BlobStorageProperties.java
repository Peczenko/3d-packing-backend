package com.packing.backend.infra.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The maximum upload size is deliberately not here: {@code StoredFile.MAX_SIZE_BYTES} is
 * the single authority for it, so duplicating the limit in configuration would let the two
 * drift.
 *
 * @param downloadUrlTtl short by design: the URL is a bearer credential, and anyone holding
 *                       it can read the blob until it expires.
 * @param enabled        set {@code false} where no storage account exists (CI, context
 *                       tests): file endpoints then fail with a clear 503 instead of the
 *                       application failing to start.
 */
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

        /** The only option under a managed identity, which holds no account key. */
        USER_DELEGATION
    }
}
