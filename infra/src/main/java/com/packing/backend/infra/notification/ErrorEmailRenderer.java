package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.ServerErrorReport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.packing.backend.infra.notification.MailFields.orAbsent;

@Component
@RequiredArgsConstructor
class ErrorEmailRenderer {

    private static final int MAX_STACK_TRACE_CHARS = 20_000;

    private static volatile String hostname;

    private final EmailTemplates                  templates;
    private final Environment                     environment;
    private final ObjectProvider<BuildProperties> buildProperties;

    EmailMessage render(ServerErrorReport report, AlertThrottle.Decision decision,
                        List<String> recipients) {
        MailFields fields = fields(report, decision);
        String stackTrace = stackTraceOf(report.cause());

        return EmailMessage.to(recipients.toArray(String[]::new))
                           .subject(subject(report, decision))
                           .html(html(report, fields, stackTrace))
                           .text(fields.asText() + "\n\nStack trace\n" + stackTrace)
                           .build();
    }

    private String subject(ServerErrorReport report, AlertThrottle.Decision decision) {
        String recurrence = decision.suppressedSinceLastAlert() > 0
                                                                    ? " (recurred " + (decision.suppressedSinceLastAlert() + 1) + "x)"
                                                                    : "";
        return "[3D Packing][" + profiles() + "] " + report.status() + " "
                + orAbsent(report.httpMethod()) + " " + orAbsent(report.uriTemplate()) + recurrence;
    }

    private MailFields fields(ServerErrorReport report, AlertThrottle.Decision decision) {
        MailFields fields = new MailFields();
        fields.put("Trace id", report.traceId());
        fields.put("When", report.occurredAt());
        fields.put("Status", report.status());
        fields.put("Request", orAbsent(report.httpMethod()) + " " + orAbsent(report.path()));
        fields.put("Handler", report.uriTemplate());
        fields.put("Exception",
                   report.cause() == null
                                          ? null
                                          : report.cause()
                                                  .getClass()
                                                  .getName());
        fields.put("Message",
                   report.cause() == null
                                          ? null
                                          : report.cause()
                                                  .getMessage());
        fields.put("User", user(report));
        fields.put("Client", orAbsent(report.clientIp()) + " · " + orAbsent(report.userAgent()));
        fields.put("Build", version() + " · profiles " + profiles() + " · host " + hostname());
        if (decision.suppressedSinceLastAlert() > 0) {
            fields.put("Recurrence",
                       decision.suppressedSinceLastAlert()
                               + " identical failures suppressed since the last alert "
                               + describeGap(decision.lastAlertAt(), report.occurredAt()));
        }
        return fields;
    }

    private String user(ServerErrorReport report) {
        if (report.firebaseUid() == null) {
            return "anonymous";
        }
        return report.firebaseUid()
                + " · " + orAbsent(report.userEmail())
                + " · " + orAbsent(report.roles());
    }

    private String describeGap(Instant lastAlertAt, Instant occurredAt) {
        if (lastAlertAt == null || occurredAt == null) {
            return "";
        }
        return "(" + Duration.between(lastAlertAt, occurredAt)
                             .toMinutes()
                + " minutes ago)";
    }

    private String html(ServerErrorReport report, MailFields fields, String stackTrace) {
        return templates.render("server-error",
                                Map.of(
                                       "status",
                                       report.status(),
                                       "handler",
                                       orAbsent(report.uriTemplate()),
                                       "request",
                                       orAbsent(report.httpMethod()) + " " + orAbsent(report.path()),
                                       "fields",
                                       fields.asMap(),
                                       "stackTrace",
                                       stackTrace,
                                       "footerNote",
                                       "You are receiving this because your address is listed in "
                                               + "app.alerts.recipients."));
    }

    private String stackTraceOf(Throwable cause) {
        if (cause == null) {
            return "(no exception attached)";
        }
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        return trace.length() <= MAX_STACK_TRACE_CHARS
                                                       ? trace
                                                       : trace.substring(0, MAX_STACK_TRACE_CHARS) + "\n… truncated";
    }

    private String version() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "unknown version" : "v" + build.getVersion();
    }

    private String profiles() {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 ? "default" : String.join(",", active);
    }

    private static String hostname() {
        String resolved = hostname;
        if (resolved == null) {
            resolved = resolveHostname();
            hostname = resolved;
        }
        return resolved;
    }

    private static String resolveHostname() {
        String fromEnvironment = System.getenv("HOSTNAME");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        try {
            return InetAddress.getLocalHost()
                              .getHostName();
        } catch (UnknownHostException e) {
            return "unknown host";
        }
    }
}
