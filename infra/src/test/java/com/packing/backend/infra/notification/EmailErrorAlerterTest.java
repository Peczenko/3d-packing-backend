package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.ServerErrorReport;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.shared.ExternalServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailErrorAlerterTest {

    private static final Instant NOW = Instant.parse("2026-07-26T14:22:08Z");
    private static final EmailMessage RENDERED =
            EmailMessage.to("ops@example.com").subject("Boom").html("<p>boom</p>").build();

    private static final Executor DIRECT = Runnable::run;

    private final EmailSender emailSender = mock(EmailSender.class);
    private final ErrorEmailRenderer renderer = mock(ErrorEmailRenderer.class);

    @Test
    void sendsTheRenderedAlert() {
        when(renderer.render(any(), any(), any())).thenReturn(RENDERED);

        alerter(List.of("ops@example.com")).alert(report(500, boom(), "/api/v1/files"));

        verify(emailSender).send(RENDERED);
    }

    @Test
    void doesNothingWhenNoRecipientIsConfigured() {
        alerter(List.of()).alert(report(500, boom(), "/api/v1/files"));

        verifyNoInteractions(renderer, emailSender);
    }

    @Test
    void doesNotReportAFailureOfTheMailProviderItself() {
        ExternalServiceException brevoDown = new ExternalServiceException("brevo", "HTTP 503");

        alerter(List.of("ops@example.com")).alert(
                report(503, new IllegalStateException("wrapped", brevoDown), "/api/v1/files"));

        verifyNoInteractions(renderer, emailSender);
    }

    @Test
    void stillReportsAFailureOfAnyOtherExternalService() {
        when(renderer.render(any(), any(), any())).thenReturn(RENDERED);
        ExternalServiceException storageDown =
                new ExternalServiceException("azure-blob-storage", "HTTP 503");

        alerter(List.of("ops@example.com")).alert(report(503, storageDown, "/api/v1/files"));

        verify(emailSender).send(RENDERED);
    }

    @Test
    void swallowsAFailureToSend() {
        when(renderer.render(any(), any(), any())).thenReturn(RENDERED);
        doThrow(new ExternalServiceException("brevo", "HTTP 500")).when(emailSender).send(any());

        alerter(List.of("ops@example.com")).alert(report(500, boom(), "/api/v1/files"));

        verify(emailSender).send(RENDERED);
    }

    @Test
    void swallowsAFailureToRender() {
        when(renderer.render(any(), any(), any())).thenThrow(new IllegalStateException("bad template"));

        alerter(List.of("ops@example.com")).alert(report(500, boom(), "/api/v1/files"));

        verifyNoInteractions(emailSender);
    }

    @Test
    void treatsTheSamePathTemplateWithDifferentIdsAsOneFailure() {
        when(renderer.render(any(), any(), any())).thenReturn(RENDERED);
        var alerter = alerter(List.of("ops@example.com"));
        RuntimeException cause = boom();

        alerter.alert(report(500, cause, "/api/v1/files/8c1f", "/api/v1/files/{fileId}"));
        alerter.alert(report(500, cause, "/api/v1/files/2b90", "/api/v1/files/{fileId}"));

        verify(emailSender, times(1)).send(any());
    }

    @Test
    void reportsDistinctFailuresSeparately() {
        when(renderer.render(any(), any(), any())).thenReturn(RENDERED);
        var alerter = alerter(List.of("ops@example.com"));

        alerter.alert(report(500, boom(), "/api/v1/files"));
        alerter.alert(report(500, new IllegalArgumentException("other"), "/api/v1/users"));

        verify(emailSender, times(2)).send(any());
    }

    @Test
    void returnsWhileTheSendIsStillInFlight() throws InterruptedException {
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(renderer.render(any(), any(), any())).thenReturn(RENDERED);
        doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await();
            return null;
        }).when(emailSender).send(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            alerter(List.of("ops@example.com"), executor)
                    .alert(report(500, boom(), "/api/v1/files"));

            // Reaching here at all means alert() returned. The latch proves the send had
            // begun on another thread and is still blocked inside it.
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseSend.countDown();
            executor.shutdownNow();
        }
    }

    private EmailErrorAlerter alerter(List<String> recipients) {
        return alerter(recipients, DIRECT);
    }

    private EmailErrorAlerter alerter(List<String> recipients, Executor executor) {
        AlertProperties properties =
                new AlertProperties(recipients, Duration.ofMinutes(15), 20);
        AlertThrottle throttle = new AlertThrottle(
                Clock.fixed(NOW, ZoneOffset.UTC), properties.cooldown(), properties.maxPerHour());
        return new EmailErrorAlerter(
                emailSender, properties, throttle, renderer, executor, "brevo");
    }

    private ServerErrorReport report(int status, Throwable cause, String uriTemplate) {
        return report(status, cause, uriTemplate, uriTemplate);
    }

    private ServerErrorReport report(int status, Throwable cause, String path, String uriTemplate) {
        return new ServerErrorReport(
                "7f3a9c21", NOW, status, "POST", path, uriTemplate,
                "203.0.113.9", "curl/8",
                "firebase-uid-1", "user@example.com", "ROLE_USER", cause);
    }

    private RuntimeException boom() {
        return new IllegalStateException("boom");
    }
}
