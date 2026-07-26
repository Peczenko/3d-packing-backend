package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.infra.shared.http.ExternalApi;
import com.packing.backend.infra.shared.http.ExternalApiErrorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrevoEmailSenderTest {

    private static final String BASE_URL = "https://api.brevo.test";
    private static final String SEND_URL = BASE_URL + "/v3/smtp/email";
    private static final String API_KEY = "secret-key";

    private MockRestServiceServer server;
    private BrevoEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        ExternalApiErrorMapper errors = new ExternalApiErrorMapper("brevo");
        builder.defaultHeader("api-key", API_KEY)
                .defaultStatusHandler(HttpStatusCode::isError, errors::raise);

        sender = new BrevoEmailSender(new ExternalApi(builder.build(), errors), properties());
    }

    @Test
    void postsTheTransactionalPayloadWithEveryOptionalPartPopulated() {
        server.expect(requestTo(SEND_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("api-key", API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "sender": {"name": "3D Packing", "email": "noreply@3dpacking.dev"},
                          "to": [{"email": "ops@example.com"}],
                          "cc": [{"email": "cc@example.com"}],
                          "bcc": [{"email": "bcc@example.com"}],
                          "replyTo": {"email": "reply@example.com"},
                          "subject": "Boom",
                          "htmlContent": "<p>boom</p>",
                          "textContent": "boom",
                          "attachment": [{"name": "trace.txt", "content": "cmVwb3J0"}]
                        }""", true))
                .andRespond(withSuccess("{\"messageId\":\"<1@brevo>\"}", MediaType.APPLICATION_JSON));

        sender.send(EmailMessage.to("ops@example.com")
                .cc("cc@example.com")
                .bcc("bcc@example.com")
                .replyTo("reply@example.com")
                .subject("Boom")
                .html("<p>boom</p>")
                .text("boom")
                .attach("trace.txt", "report".getBytes(StandardCharsets.UTF_8))
                .build());

        server.verify();
    }

    @Test
    void omitsOptionalMembersEntirelyWhenTheyAreUnset() {
        server.expect(requestTo(SEND_URL))
                .andExpect(content().json("""
                        {
                          "sender": {"name": "3D Packing", "email": "noreply@3dpacking.dev"},
                          "to": [{"email": "ops@example.com"}],
                          "subject": "Boom",
                          "htmlContent": "<p>boom</p>"
                        }""", true))
                .andRespond(withSuccess());

        sender.send(EmailMessage.to("ops@example.com").subject("Boom").html("<p>boom</p>").build());

        server.verify();
    }

    @Test
    void reportsARejectedKeyAsAnExternalServiceFailureWithoutLeakingTheKey() {
        server.expect(requestTo(SEND_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        Throwable thrown = catchThrowable(() -> sender.send(
                EmailMessage.to("ops@example.com").subject("Boom").html("<p>x</p>").build()));

        assertThat(thrown).isInstanceOf(ExternalServiceException.class);
        assertThat(((ExternalServiceException) thrown).service()).isEqualTo("brevo");
        assertThat(thrown.getMessage()).contains("401", "unauthorized").doesNotContain(API_KEY);
    }

    @Test
    void refusesAttachmentsOverTheProviderLimitBeforeCallingOut() {
        byte[] oversized = new byte[11 * 1024 * 1024];
        oversized[0] = 1;

        assertThatThrownBy(() -> sender.send(EmailMessage.to("ops@example.com")
                .subject("Boom")
                .html("<p>x</p>")
                .attach("huge.bin", oversized)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("over Brevo's limit");

        server.verify();
    }

    private EmailProperties properties() {
        return new EmailProperties(true, "noreply@3dpacking.dev", "3D Packing",
                new EmailProperties.Brevo(BASE_URL, API_KEY,
                        Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
