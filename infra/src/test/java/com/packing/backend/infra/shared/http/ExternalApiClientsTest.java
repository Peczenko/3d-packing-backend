package com.packing.backend.infra.shared.http;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ExternalApiClientsTest {

    private HttpServer server;
    private ExternalApiClients apiClients;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        apiClients = new ExternalApiClients(
                RestClient.builder(),
                ClientHttpRequestFactoryBuilder.detect(),
                ClientHttpRequestFactorySettings.defaults());
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

        api.client().get().uri("/v3/account").retrieve().toBodilessEntity();

        assertThat(lastExchange.get().getRequestURI().getPath()).isEqualTo("/v3/account");
        assertThat(lastExchange.get().getRequestHeaders().getFirst("api-key"))
                .isEqualTo("secret-key");
    }

    @Test
    void translatesANonSuccessResponseWithoutTheAdapterHandlingAnything() {
        respondWith(401, "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}");
        ExternalApi api = apiClients.create(spec(baseUrl()), builder -> { });

        assertThatThrownBy(() -> api.client().get().uri("/v3/account").retrieve().toBodilessEntity())
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("brevo")
                .hasMessageContaining("401")
                .hasMessageContaining("unauthorized");
    }

    @Test
    void truncatesAnOversizedErrorBody() {
        respondWith(500, "x".repeat(5_000));
        ExternalApi api = apiClients.create(spec(baseUrl()), builder -> { });

        Throwable thrown = catchThrowable(
                () -> api.client().get().uri("/v3/account").retrieve().toBodilessEntity());

        assertThat(thrown).isInstanceOf(ExternalServiceException.class);
        assertThat(thrown.getMessage()).contains("truncated").hasSizeLessThan(2_000);
    }

    @Test
    void translatesAConnectionFailure() {
        server.stop(0);
        ExternalApi api = apiClients.create(spec(baseUrl()), builder -> { });
        Supplier<Object> call =
                () -> api.client().get().uri("/v3/account").retrieve().toBodilessEntity();

        assertThatThrownBy(() -> api.call(call))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("brevo");
    }

    @Test
    void refusesASpecWithoutAServiceName() {
        assertThatThrownBy(() -> new ExternalApiSpec(
                " ", "https://example.test", Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceName");
    }

    @Test
    void refusesASpecWithANonPositiveTimeout() {
        assertThatThrownBy(() -> new ExternalApiSpec(
                "brevo", "https://example.test", Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
    }

    private ExternalApiSpec spec(String baseUrl) {
        return new ExternalApiSpec("brevo", baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respondWith(int status, String body) {
        HttpHandler handler = exchange -> {
            lastExchange.set(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
        server.createContext("/", handler);
    }
}
