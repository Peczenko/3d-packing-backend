package com.packing.backend.infra.shared.http;

import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

public record ExternalApi(RestClient client, ExternalApiErrorMapper errors) {

    public <T> T call(Supplier<T> action) {
        return errors.translating(action);
    }
}
