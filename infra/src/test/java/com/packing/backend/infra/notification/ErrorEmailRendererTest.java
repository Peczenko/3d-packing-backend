package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.ServerErrorReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ErrorEmailRendererTest {

    private static final Instant WHEN = Instant.parse("2026-07-26T14:22:08Z");
    private static final List<String> RECIPIENTS = List.of("ops@example.com");
    private static final AlertThrottle.Decision FIRST_SIGHTING =
            new AlertThrottle.Decision(true, 0, null);

    private final MockEnvironment environment = new MockEnvironment();
    private final ErrorEmailRenderer renderer =
            new ErrorEmailRenderer(new EmailTemplates(), environment, noBuildInfo());

    @Test
    void putsTheStatusMethodAndHandlerTemplateInTheSubject() {
        environment.setActiveProfiles("azure");

        EmailMessage message = renderer.render(report(500, boom()), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.subject())
                .isEqualTo("[3D Packing][azure] 500 POST /api/v1/files/{fileId}");
        assertThat(message.to()).containsExactly("ops@example.com");
    }

    @Test
    void countsTheOriginalAlongsideTheSuppressedRepeatsInTheSubject() {
        AlertThrottle.Decision recurring =
                new AlertThrottle.Decision(true, 46, WHEN.minusSeconds(900));

        EmailMessage message = renderer.render(report(500, boom()), recurring, RECIPIENTS);

        assertThat(message.subject()).endsWith("(recurred 47x)");
        assertThat(message.htmlBody()).contains("46 identical failures suppressed");
    }

    @Test
    void escapesMarkupCarriedInTheExceptionMessage() {
        RuntimeException hostile = new IllegalStateException("<script>alert('xss')</script>");

        EmailMessage message = renderer.render(report(500, hostile), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    void reportsTheCallerWhenOneIsAuthenticated() {
        EmailMessage message = renderer.render(report(500, boom()), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody()).contains("firebase-uid-1", "user@example.com", "ROLE_USER");
    }

    @Test
    void reportsAnonymousWhenTheFailureHadNoPrincipal() {
        ServerErrorReport anonymous = new ServerErrorReport(
                "7f3a9c21", WHEN, 500, "POST", "/api/v1/files", "/api/v1/files",
                "203.0.113.9", "curl/8", null, null, null, boom());

        EmailMessage message = renderer.render(anonymous, FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody()).contains("anonymous");
    }

    @Test
    void carriesTheTraceIdAndTheTimestamp() {
        EmailMessage message = renderer.render(report(503, boom()), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody()).contains("7f3a9c21", "2026-07-26T14:22:08Z");
        assertThat(message.textBody()).contains("Trace id: 7f3a9c21");
    }

    @Test
    void truncatesAnUnreasonablyLongStackTrace() {
        RuntimeException deep = new IllegalStateException("deep");
        StackTraceElement[] frames = new StackTraceElement[5_000];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("com.packing.backend.Deep", "call", "Deep.java", i);
        }
        deep.setStackTrace(frames);

        EmailMessage message = renderer.render(report(500, deep), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.textBody()).contains("… truncated");
        assertThat(message.textBody()).hasSizeLessThan(25_000);
    }

    @Test
    void wrapsTheBodyInTheSharedLayout() {
        EmailMessage message = renderer.render(report(500, boom()), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody())
                .contains("<style>")
                .contains("class=\"card\"")
                .contains("app.alerts.recipients")
                .doesNotContain("th:text", "th:each", "th:replace");
    }

    @Test
    void stripsTheTemplateAuthoringCommentsFromTheSentBody() {
        EmailMessage message = renderer.render(report(500, boom()), FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody()).doesNotContain("<!--");
    }

    @Test
    void survivesAReportWithNoExceptionAttached() {
        ServerErrorReport withoutCause = new ServerErrorReport(
                "7f3a9c21", WHEN, 500, "GET", "/api/v1/files", "/api/v1/files",
                null, null, null, null, null, null);

        EmailMessage message = renderer.render(withoutCause, FIRST_SIGHTING, RECIPIENTS);

        assertThat(message.htmlBody()).contains("no exception attached");
    }

    private ServerErrorReport report(int status, Throwable cause) {
        return new ServerErrorReport(
                "7f3a9c21", WHEN, status, "POST",
                "/api/v1/files/8c1f?draft=true", "/api/v1/files/{fileId}",
                "203.0.113.9", "curl/8",
                "firebase-uid-1", "user@example.com", "ROLE_USER", cause);
    }

    private RuntimeException boom() {
        return new IllegalStateException("boom", new IllegalArgumentException("root"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<BuildProperties> noBuildInfo() {
        return mock(ObjectProvider.class);
    }
}
