package com.packing.backend.infra.firebase;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "firebase")
public record FirebaseProperties(
        @NotBlank String projectId,
        String serviceAccount,
        @DefaultValue("true") boolean adminEnabled) {

    public String issuerUri() {
        return "https://securetoken.google.com/" + projectId;
    }

    public String audience() {
        return projectId;
    }
}
