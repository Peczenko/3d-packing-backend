package com.packing.backend.infra.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.packing.backend.core.notification.EmailAttachment;
import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.infra.shared.http.ExternalApi;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;

import java.util.Base64;
import java.util.List;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class BrevoEmailSender implements EmailSender {

    private static final String SEND_PATH = "/v3/smtp/email";

    private static final int  MAX_RECIPIENTS_WITH_ATTACHMENTS           = 99;
    private static final long APPLICATION_ATTACHMENT_SAFETY_LIMIT_BYTES = 10L * 1024 * 1024;

    private final ExternalApi     brevo;
    private final EmailProperties properties;

    @Override
    public void send(EmailMessage message) {
        SendRequest request = toRequest(message);
        brevo.execute(client -> client.post()
                                      .uri(SEND_PATH)
                                      .contentType(MediaType.APPLICATION_JSON)
                                      .body(request)
                                      .retrieve()
                                      .toBodilessEntity());
    }

    private SendRequest toRequest(EmailMessage message) {
        validateAttachmentRecipients(message);
        return new SendRequest(
                               new Contact(properties.fromName(), properties.fromAddress()),
                               contacts(message.to()),
                               contacts(message.cc()),
                               contacts(message.bcc()),
                               message.replyTo() == null ? null : new Contact(null, message.replyTo()),
                               message.subject(),
                               message.htmlBody(),
                               message.textBody(),
                               attachments(message.attachments()));
    }

    private List<Contact> contacts(List<String> addresses) {
        return addresses.stream()
                        .map(address -> new Contact(null, address))
                        .toList();
    }

    private List<Attachment> attachments(List<EmailAttachment> attachments) {
        long total = attachments.stream()
                                .mapToLong(EmailAttachment::sizeBytes)
                                .sum();
        if (total > APPLICATION_ATTACHMENT_SAFETY_LIMIT_BYTES) {
            throw new IllegalArgumentException(
                                               "Attachments total " + total + " bytes, over the application's raw "
                                                       + "attachment safety limit of "
                                                       + APPLICATION_ATTACHMENT_SAFETY_LIMIT_BYTES);
        }
        return attachments.stream()
                          .map(attachment -> new Attachment(
                                                            attachment.fileName(),
                                                            Base64.getEncoder()
                                                                  .encodeToString(attachment.content())))
                          .toList();
    }

    private void validateAttachmentRecipients(EmailMessage message) {
        if (message.attachments()
                   .isEmpty()) {
            return;
        }
        int recipients = message.to()
                                .size()
                + message.cc()
                         .size()
                + message.bcc()
                         .size();
        if (recipients > MAX_RECIPIENTS_WITH_ATTACHMENTS) {
            throw new IllegalArgumentException(
                                               "Brevo allows at most " + MAX_RECIPIENTS_WITH_ATTACHMENTS
                                                       + " recipients when an API email contains attachments");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record SendRequest(
            Contact sender,
            List<Contact> to,
            List<Contact> cc,
            List<Contact> bcc,
            Contact replyTo,
            String subject,
            String htmlContent,
            String textContent,
            List<Attachment> attachment) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record Contact(String name, String email) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record Attachment(String name, String content) {
    }
}
