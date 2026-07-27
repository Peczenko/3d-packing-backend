package com.packing.backend.infra.shared.http;

import java.net.URI;
import java.time.Duration;

public record ExternalApiSpec(
        String serviceName,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public ExternalApiSpec {
        requireText(serviceName, "serviceName");
        requireHttpBaseUrl(baseUrl);
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireHttpBaseUrl(URI value) {
        if (value == null || !value.isAbsolute()
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URI without credentials, query, or fragment");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
