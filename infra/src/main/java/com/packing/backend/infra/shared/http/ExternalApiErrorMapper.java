package com.packing.backend.infra.shared.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.shared.ExternalHttpException;
import com.packing.backend.core.shared.ExternalServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public final class ExternalApiErrorMapper {

    private static final int MAX_BODY_CHARS = 1_000;

    private final String       serviceName;
    private final ObjectMapper objectMapper;

    public ExternalApiErrorMapper(String serviceName, ObjectMapper objectMapper) {
        this.serviceName = serviceName;
        this.objectMapper = objectMapper;
    }

    public <T> T translating(Supplier<T> call) {
        try {
            return call.get();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ExternalServiceException(
                                               serviceName,
                                               "Call to '" + serviceName + "' failed: " + e.getMessage(),
                                               e);
        }
    }

    public void translating(Runnable call) {
        translating(() -> {
            call.run();
            return null;
        });
    }

    public void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String body = responseBody(response);
        String path = request.getURI()
                             .getRawPath();
        String message = "Call to '" + serviceName + "' failed: "
                + request.getMethod() + " " + request.getURI()
                                                     .getPath()
                + " returned " + status.value() + " " + response.getStatusText()
                + bodyFragment(body);
        throw new ExternalHttpException(
                                        serviceName,
                                        status.value(),
                                        request.getMethod()
                                               .name(),
                                        path == null || path.isBlank() ? "/" : path,
                                        providerCode(body),
                                        response.getHeaders()
                                                .getFirst(HttpHeaders.RETRY_AFTER),
                                        isRetryable(status.value()),
                                        message);
    }

    private String responseBody(ClientHttpResponse response) {
        try {
            return new String(response.getBody()
                                      .readNBytes(MAX_BODY_CHARS * 4),
                              StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return " (response body unreadable: " + e.getMessage() + ")";
        }
    }

    private String bodyFragment(String body) {
        if (body.isEmpty()) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS
                                               ? ": " + body
                                               : ": " + body.substring(0, MAX_BODY_CHARS) + "… (truncated)";
    }

    private String providerCode(String body) {
        if (body.isEmpty()) {
            return null;
        }
        try {
            JsonNode code = objectMapper.readTree(body)
                                        .get("code");
            return code == null || code.isContainerNode() || code.isNull() ? null : code.asText();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }
}
