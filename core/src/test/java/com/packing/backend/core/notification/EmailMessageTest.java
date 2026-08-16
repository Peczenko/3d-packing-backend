package com.packing.backend.core.notification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailMessageTest {

    private static final byte[] CONTENT = "report".getBytes(StandardCharsets.UTF_8);

    @Test
    void buildsAMessageThroughTheBuilder() {
        EmailMessage message = EmailMessage.to("a@example.com", "b@example.com")
                                           .cc("c@example.com")
                                           .bcc("d@example.com")
                                           .replyTo("reply@example.com")
                                           .subject("Subject")
                                           .html("<p>body</p>")
                                           .text("body")
                                           .attach("trace.txt", CONTENT)
                                           .build();

        assertThat(message.to()).containsExactly("a@example.com", "b@example.com");
        assertThat(message.cc()).containsExactly("c@example.com");
        assertThat(message.bcc()).containsExactly("d@example.com");
        assertThat(message.replyTo()).isEqualTo("reply@example.com");
        assertThat(message.htmlBody()).isEqualTo("<p>body</p>");
        assertThat(message.textBody()).isEqualTo("body");
        assertThat(message.attachments()).singleElement()
                                         .extracting(EmailAttachment::fileName)
                                         .isEqualTo("trace.txt");
    }

    @Test
    void defaultsTheOptionalCollectionsToEmptyRatherThanNull() {
        EmailMessage message = EmailMessage.to("a@example.com")
                                           .subject("Subject")
                                           .text("body")
                                           .build();

        assertThat(message.cc()).isEmpty();
        assertThat(message.bcc()).isEmpty();
        assertThat(message.attachments()).isEmpty();
        assertThat(message.htmlBody()).isNull();
    }

    @Test
    void rejectsAMessageWithNoRecipient() {
        assertThatThrownBy(() -> new EmailMessage(
                                                  List.of(),
                                                  null,
                                                  null,
                                                  null,
                                                  "Subject",
                                                  "<p>x</p>",
                                                  null,
                                                  null))
                                                        .isInstanceOf(IllegalArgumentException.class)
                                                        .hasMessageContaining("at least one recipient");
    }

    @Test
    void rejectsABlankRecipient() {
        assertThatThrownBy(() -> EmailMessage.to("a@example.com", " ")
                                             .subject("Subject")
                                             .text("body")
                                             .build())
                                                      .isInstanceOf(IllegalArgumentException.class)
                                                      .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsABlankSubject() {
        assertThatThrownBy(() -> EmailMessage.to("a@example.com")
                                             .subject(" ")
                                             .text("body")
                                             .build())
                                                      .isInstanceOf(IllegalArgumentException.class)
                                                      .hasMessageContaining("subject");
    }

    @Test
    void rejectsAMessageWithNeitherBody() {
        assertThatThrownBy(() -> EmailMessage.to("a@example.com")
                                             .subject("Subject")
                                             .build())
                                                      .isInstanceOf(IllegalArgumentException.class)
                                                      .hasMessageContaining("HTML or a text body");
    }

    @Test
    void doesNotShareTheRecipientListWithTheCaller() {
        List<String> recipients = new ArrayList<>(List.of("a@example.com"));

        EmailMessage message = new EmailMessage(
                                                recipients,
                                                null,
                                                null,
                                                null,
                                                "Subject",
                                                null,
                                                "body",
                                                null);
        recipients.add("intruder@example.com");

        assertThat(message.to()).containsExactly("a@example.com");
    }

    @Test
    void doesNotShareTheAttachmentBytesWithTheCaller() {
        byte[] mutable = "report".getBytes(StandardCharsets.UTF_8);
        EmailAttachment attachment = new EmailAttachment("trace.txt", mutable);

        mutable[0] = 'X';

        assertThat(attachment.content()).isEqualTo(CONTENT);
    }

    @Test
    void rejectsAnEmptyAttachment() {
        assertThatThrownBy(() -> new EmailAttachment("trace.txt", new byte[0]))
                                                                               .isInstanceOf(IllegalArgumentException.class)
                                                                               .hasMessageContaining("empty");
    }
}
