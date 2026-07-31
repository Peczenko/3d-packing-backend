package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.ServerErrorReport;
import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.infra.shared.Causes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Slf4j
class EmailErrorAlerter implements ErrorAlerter, AutoCloseable {

    private static final String APPLICATION_PACKAGE = "com.packing.backend";
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final EmailSender emailSender;
    private final AlertProperties properties;
    private final AlertThrottle throttle;
    private final ErrorEmailRenderer renderer;
    private final Executor executor;
    private final String mailServiceName;

    @Override
    public void alert(ServerErrorReport report) {
        try {
            if (!properties.alertingEnabled() || causedByMailProvider(report)) {
                return;
            }

            AlertThrottle.Decision decision = throttle.evaluate(fingerprint(report));
            if (!decision.send()) {
                return;
            }
            executor.execute(() -> renderAndSend(report, decision));
        } catch (RuntimeException e) {
            log.error("Could not raise an alert for trace {}. The original failure is logged "
                    + "separately and is unaffected.", report.traceId(), e);
        }
    }

    private void renderAndSend(ServerErrorReport report, AlertThrottle.Decision decision) {
        try {
            emailSender.send(renderer.render(report, decision, properties.recipients()));
        } catch (RuntimeException e) {
            log.error("Alert email for trace {} could not be sent", report.traceId(), e);
        }
    }

    private boolean causedByMailProvider(ServerErrorReport report) {
        return Causes.firstOfType(report.cause(), ExternalServiceException.class)
                .filter(external -> mailServiceName.equals(external.service()))
                .isPresent();
    }

    private String fingerprint(ServerErrorReport report) {
        Throwable cause = report.cause();
        return report.status()
                + ":" + (cause == null ? "none" : cause.getClass().getName())
                + ":" + originFrame(cause)
                + ":" + report.uriTemplate();
    }

    @Override
    public void close() {
        if (!(executor instanceof ExecutorService service)) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            service.shutdownNow();
        }
    }

    private String originFrame(Throwable cause) {
        for (Throwable current : Causes.chainOf(cause)) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith(APPLICATION_PACKAGE)) {
                    return frame.getClassName() + "." + frame.getMethodName()
                            + ":" + frame.getLineNumber();
                }
            }
        }
        return "unknown";
    }
}
