package com.packing.backend.infra.shared.http;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.autoconfigure.http.client.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ExternalHttpConfig {

    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> disableImplicitHttpRetries() {
        return builder -> builder.withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries);
    }
}
