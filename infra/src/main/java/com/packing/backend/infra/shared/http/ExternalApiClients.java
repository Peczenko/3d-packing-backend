package com.packing.backend.infra.shared.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class ExternalApiClients {

    private final RestClient.Builder restClientBuilder;
    private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;
    private final ClientHttpRequestFactorySettings requestFactorySettings;
    private final ObjectMapper objectMapper;

    public ExternalApi create(ExternalApiSpec spec) {
        return create(spec, builder -> { });
    }

    public ExternalApi create(ExternalApiSpec spec, Consumer<RestClient.Builder> customizer) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(customizer, "customizer");
        ExternalApiErrorMapper errors = new ExternalApiErrorMapper(spec.serviceName(), objectMapper);

        RestClient.Builder builder = restClientBuilder.clone()
                .baseUrl(spec.baseUrl())
                .requestFactory(requestFactoryBuilder.build(requestFactorySettings
                        .withConnectTimeout(spec.connectTimeout())
                        .withReadTimeout(spec.readTimeout())))
                .defaultStatusHandler(HttpStatusCode::isError, errors::raise);

        customizer.accept(builder);
        return new ExternalApi(builder.build(), errors);
    }
}
