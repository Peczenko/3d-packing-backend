package com.packing.backend.infra.shared.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.shared.ExternalServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalApiErrorMapperTest {

    private final ExternalApiErrorMapper mapper =
            new ExternalApiErrorMapper("brevo", new ObjectMapper());

    @Test
    void returnsTheValueWhenTheCallSucceeds() {
        assertThat(mapper.translating(() -> "sent")).isEqualTo("sent");
    }

    @Test
    void translatesATransportFailureIntoAnExternalServiceException() {
        Supplier<String> failing = () -> {
            throw new ResourceAccessException("connect timed out");
        };

        assertThatThrownBy(() -> mapper.translating(failing))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("brevo")
                .hasMessageContaining("connect timed out")
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void leavesAnAlreadyTranslatedExceptionUntouched() {
        ExternalServiceException original = new ExternalServiceException("brevo", "HTTP 401");
        Supplier<String> failing = () -> {
            throw original;
        };

        assertThatThrownBy(() -> mapper.translating(failing)).isSameAs(original);
    }

    @Test
    void doesNotInterceptFailuresThatAreNotTransportFailures() {
        Supplier<String> failing = () -> {
            throw new IllegalArgumentException("attachment too large");
        };

        assertThatThrownBy(() -> mapper.translating(failing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runsTheRunnableOverload() {
        AtomicBoolean ran = new AtomicBoolean();

        mapper.translating(() -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void translatesATransportFailureFromTheRunnableOverload() {
        Runnable failing = () -> {
            throw new ResourceAccessException("read timed out", new IOException("boom"));
        };

        assertThatThrownBy(() -> mapper.translating(failing))
                .isInstanceOf(ExternalServiceException.class);
    }
}
