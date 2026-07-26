package com.packing.backend.infra.shared.http;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class ExternalApiClients {

    private final RestClient.Builder restClientBuilder;
    private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;
    private final ClientHttpRequestFactorySettings requestFactorySettings;

    public ExternalApi create(ExternalApiSpec spec, Consumer<RestClient.Builder> customizer) {
        ExternalApiErrorMapper errors = new ExternalApiErrorMapper(spec.serviceName());

        RestClient.Builder builder = restClientBuilder.clone()
                .baseUrl(spec.baseUrl())
                .requestFactory(requestFactoryBuilder.build(requestFactorySettings
                        .withConnectTimeout(spec.connectTimeout())
                        .withReadTimeout(spec.readTimeout())))
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), errors::raise);

        customizer.accept(builder);
        return new ExternalApi(builder.build(), errors);
    }
}
