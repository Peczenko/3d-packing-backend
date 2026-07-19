package com.packing.backend.infra.firebase;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Firebase configuration. In the {@code azure} profile these are sourced from Azure Key
 * Vault — see {@code application-azure.properties} for why the values are read through
 * placeholder indirection rather than bound directly.
 *
 * @param projectId      the Firebase project id. Required even when the Admin SDK is
 *                       disabled, because ID-token verification derives the issuer and
 *                       the expected audience from it.
 * @param serviceAccount service-account JSON, base64-encoded (raw JSON is also accepted).
 *                       Blank falls back to Application Default Credentials.
 * @param adminEnabled   whether to initialise the Admin SDK at all. Set to {@code false}
 *                       where no credentials exist (CI, tests): token verification keeps
 *                       working, only Firebase-side mutations become unavailable.
 */
@Validated
@ConfigurationProperties(prefix = "firebase")
public record FirebaseProperties(
        @NotBlank String projectId,
        String serviceAccount,
        @DefaultValue("true") boolean adminEnabled) {

    /** Issuer of Firebase ID tokens for this project, per Firebase's token spec. */
    public String issuerUri() {
        return "https://securetoken.google.com/" + projectId;
    }

    /** Firebase sets {@code aud} to the bare project id. */
    public String audience() {
        return projectId;
    }
}
