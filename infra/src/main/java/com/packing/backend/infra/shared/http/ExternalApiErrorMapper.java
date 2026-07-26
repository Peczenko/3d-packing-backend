package com.packing.backend.infra.shared.http;

import com.packing.backend.core.shared.ExternalServiceException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public final class ExternalApiErrorMapper {

    private static final int MAX_BODY_CHARS = 1_000;

    private final String serviceName;

    public ExternalApiErrorMapper(String serviceName) {
        this.serviceName = serviceName;
    }

    public <T> T translating(Supplier<T> call) {
        try {
            return call.get();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ExternalServiceException(
                    serviceName, "Call to '" + serviceName + "' failed: " + e.getMessage(), e);
        }
    }

    public void translating(Runnable call) {
        translating(() -> {
            call.run();
            return null;
        });
    }

    public void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new ExternalServiceException(serviceName, "Call to '" + serviceName + "' failed: "
                + request.getMethod() + " " + request.getURI().getPath()
                + " returned " + response.getStatusCode().value() + " " + response.getStatusText()
                + bodyFragment(response));
    }

    private String bodyFragment(ClientHttpResponse response) {
        String body;
        try {
            body = new String(response.getBody().readNBytes(MAX_BODY_CHARS * 4),
                    StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return " (response body unreadable: " + e.getMessage() + ")";
        }
        if (body.isEmpty()) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS
                ? ": " + body
                : ": " + body.substring(0, MAX_BODY_CHARS) + "… (truncated)";
    }
}
