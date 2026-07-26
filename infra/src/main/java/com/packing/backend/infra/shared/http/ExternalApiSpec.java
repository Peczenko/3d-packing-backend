package com.packing.backend.infra.shared.http;

import java.time.Duration;

public record ExternalApiSpec(
        String serviceName,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public ExternalApiSpec {
        requireText(serviceName, "serviceName");
        requireText(baseUrl, "baseUrl");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
