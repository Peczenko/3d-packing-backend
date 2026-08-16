package com.packing.backend.infra.shared.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.shared.ExternalHttpException;
import com.packing.backend.core.shared.ExternalServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ExternalApiClientsTest {

    private HttpServer                          server;
    private ExternalApiClients                  apiClients;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
    private final AtomicInteger                 requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        apiClients = new ExternalApiClients(
                                            RestClient.builder(),
                                            requestFactoryBuilder(),
                                            ClientHttpRequestFactorySettings.defaults(),
                                            new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void resolvesRequestsAgainstTheBaseUrlAndAppliesTheCustomizer() {
        respondWith(200, "{\"messageId\":\"1\"}");
        ExternalApi api = apiClients.create(spec(baseUrl()),
                                            builder -> builder.defaultHeader("api-key", "secret-key"));

        api.execute(client -> client.get()
                                    .uri("/v3/account")
                                    .retrieve()
                                    .toBodilessEntity());

        assertThat(lastExchange.get()
                               .getRequestURI()
                               .getPath()).isEqualTo("/v3/account");
        assertThat(lastExchange.get()
                               .getRequestHeaders()
                               .getFirst("api-key"))
                                                    .isEqualTo("secret-key");
    }

    @Test
    void translatesANonSuccessResponseWithoutTheAdapterHandlingAnything() {
        respondWith(401, "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}");
        ExternalApi api = apiClients.create(spec(baseUrl()), builder -> {
        });

        ExternalHttpException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                                                                                            () -> api.execute(client -> client.get()
                                                                                                                              .uri("/v3/account")
                                                                                                                              .retrieve()
                                                                                                                              .toBodilessEntity()),
                                                                                            ExternalHttpException.class);

        assertThat(thrown.service()).isEqualTo("brevo");
        assertThat(thrown.statusCode()).isEqualTo(401);
        assertThat(thrown.method()).isEqualTo("GET");
        assertThat(thrown.path()).isEqualTo("/v3/account");
        assertThat(thrown.providerCode()).isEqualTo("unauthorized");
        assertThat(thrown.retryAfter()).isNull();
        assertThat(thrown.retryable()).isFalse();
        assertThat(thrown).hasMessageContaining("401")
                          .hasMessageContaining("unauthorized");
    }

    @Test
    void truncatesAnOversizedErrorBody() {
        respondWith(500, "x".repeat(5_000));
        ExternalApi api = apiClients.create(spec(baseUrl()), builder -> {
        });

        Throwable thrown = catchThrowable(
                                          () -> api.execute(client -> client.get()
                                                                            .uri("/v3/account")
                                                                            .retrieve()
                                                                            .toBodilessEntity()));

        assertThat(thrown).isInstanceOf(ExternalHttpException.class);
        assertThat(thrown.getMessage()).contains("truncated")
                                       .hasSizeLessThan(2_000);
    }

    @Test
    void exposesRetryMetadataWithoutMessageParsing() {
        respondWith(429,
                    "{\"code\":\"rate_limited\",\"message\":\"Slow down\"}",
                    Map.of("Retry-After", "30"));
        ExternalApi api = apiClients.create(spec(baseUrl()));

        ExternalHttpException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                                                                                            () -> api.execute(client -> client.get()
                                                                                                                              .uri("/v3/account")
                                                                                                                              .retrieve()
                                                                                                                              .toBodilessEntity()),
                                                                                            ExternalHttpException.class);

        assertThat(thrown.statusCode()).isEqualTo(429);
        assertThat(thrown.providerCode()).isEqualTo("rate_limited");
        assertThat(thrown.retryAfter()).isEqualTo("30");
        assertThat(thrown.retryable()).isTrue();
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void translatesAConnectionFailureWithoutExposingTheRawClient() {
        server.stop(0);
        ExternalApi api = apiClients.create(spec(baseUrl()), builder -> {
        });

        assertThatThrownBy(() -> api.execute(client -> client.get()
                                                             .uri("/v3/account")
                                                             .retrieve()
                                                             .toBodilessEntity()))
                                                                                  .isInstanceOf(ExternalServiceException.class)
                                                                                  .hasMessageContaining("brevo");
    }

    @Test
    void appliesAndTranslatesTheConfiguredReadTimeout() {
        respondAfter(Duration.ofMillis(300), 200, "{}");
        ExternalApi api = apiClients.create(
                                            new ExternalApiSpec("brevo",
                                                                URI.create(baseUrl()),
                                                                Duration.ofSeconds(2),
                                                                Duration.ofMillis(30)));

        assertThatThrownBy(() -> api.execute(client -> client.get()
                                                             .uri("/v3/account")
                                                             .retrieve()
                                                             .toBodilessEntity()))
                                                                                  .isInstanceOf(ExternalServiceException.class)
                                                                                  .hasMessageContaining("brevo");
    }

    @Test
    void refusesASpecWithoutAServiceName() {
        assertThatThrownBy(() -> new ExternalApiSpec(
                                                     " ",
                                                     URI.create("https://example.test"),
                                                     Duration.ofSeconds(1),
                                                     Duration.ofSeconds(1)))
                                                                            .isInstanceOf(IllegalArgumentException.class)
                                                                            .hasMessageContaining("serviceName");
    }

    @Test
    void refusesABaseUrlThatCanCarryCredentialsOrRequestSpecificParts() {
        assertThatThrownBy(() -> new ExternalApiSpec(
                                                     "brevo",
                                                     URI.create("https://user@example.test/v3?token=secret"),
                                                     Duration.ofSeconds(1),
                                                     Duration.ofSeconds(1)))
                                                                            .isInstanceOf(IllegalArgumentException.class)
                                                                            .hasMessageContaining("baseUrl");
    }

    @Test
    void refusesASpecWithANonPositiveTimeout() {
        assertThatThrownBy(() -> new ExternalApiSpec(
                                                     "brevo",
                                                     URI.create("https://example.test"),
                                                     Duration.ZERO,
                                                     Duration.ofSeconds(1)))
                                                                            .isInstanceOf(IllegalArgumentException.class)
                                                                            .hasMessageContaining("connectTimeout");
    }

    private ExternalApiSpec spec(String baseUrl) {
        return new ExternalApiSpec(
                                   "brevo",
                                   URI.create(baseUrl),
                                   Duration.ofSeconds(2),
                                   Duration.ofSeconds(2));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress()
                                           .getPort();
    }

    private void respondWith(int status, String body) {
        respondWith(status, body, Map.of());
    }

    private void respondWith(int status, String body, Map<String, String> headers) {
        HttpHandler handler = exchange -> {
            requestCount.incrementAndGet();
            lastExchange.set(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .add("Content-Type", "application/json");
            headers.forEach((name, value) -> exchange.getResponseHeaders()
                                                     .add(name, value));
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody()
                    .write(bytes);
            exchange.close();
        };
        server.createContext("/", handler);
    }

    private void respondAfter(Duration delay, int status, String body) {
        HttpHandler handler = exchange -> {
            requestCount.incrementAndGet();
            lastExchange.set(exchange);
            LockSupport.parkNanos(delay.toNanos());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody()
                        .write(bytes);
            } finally {
                exchange.close();
            }
        };
        server.createContext("/", handler);
    }

    private ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder() {
        var builder = ClientHttpRequestFactoryBuilder.httpComponents();
        return new ExternalHttpConfig().disableImplicitHttpRetries()
                                       .customize(builder);
    }
}
