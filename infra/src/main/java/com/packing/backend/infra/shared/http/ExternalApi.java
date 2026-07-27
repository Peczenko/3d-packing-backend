package com.packing.backend.infra.shared.http;

import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.function.Function;

public final class ExternalApi {

    private final RestClient client;
    private final ExternalApiErrorMapper errors;

    ExternalApi(RestClient client, ExternalApiErrorMapper errors) {
        this.client = Objects.requireNonNull(client, "client");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    public <T> T execute(Function<RestClient, T> action) {
        Objects.requireNonNull(action, "action");
        return errors.translating(() -> action.apply(client));
    }
}
