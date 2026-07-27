package com.packing.backend.core.notification;

import java.util.ArrayList;
import java.util.List;

public record EmailMessage(
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String replyTo,
        String subject,
        String htmlBody,
        String textBody,
        List<EmailAttachment> attachments) {

    public EmailMessage {
        to = requireRecipients(to);
        cc = copyOrEmpty(cc);
        bcc = copyOrEmpty(bcc);
        attachments = copyOrEmpty(attachments);

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Email subject must not be blank");
        }
        if (isBlank(htmlBody) && isBlank(textBody)) {
            throw new IllegalArgumentException("Email must carry an HTML or a text body");
        }
    }

    public static Builder to(String... recipients) {
        return new Builder().to(recipients);
    }

    private static List<String> requireRecipients(List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("Email must have at least one recipient");
        }
        if (recipients.stream().anyMatch(EmailMessage::isBlank)) {
            throw new IllegalArgumentException("Email recipients must not be blank");
        }
        return List.copyOf(recipients);
    }

    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class Builder {

        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> bcc = new ArrayList<>();
        private final List<EmailAttachment> attachments = new ArrayList<>();
        private String replyTo;
        private String subject;
        private String htmlBody;
        private String textBody;

        public Builder to(String... recipients) {
            return addAll(to, recipients);
        }

        public Builder to(List<String> recipients) {
            to.addAll(recipients);
            return this;
        }

        public Builder cc(String... recipients) {
            return addAll(cc, recipients);
        }

        public Builder bcc(String... recipients) {
            return addAll(bcc, recipients);
        }

        public Builder replyTo(String address) {
            this.replyTo = address;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder html(String htmlBody) {
            this.htmlBody = htmlBody;
            return this;
        }

        public Builder text(String textBody) {
            this.textBody = textBody;
            return this;
        }

        public Builder attach(String fileName, byte[] content) {
            return attach(new EmailAttachment(fileName, content));
        }

        public Builder attach(EmailAttachment attachment) {
            attachments.add(attachment);
            return this;
        }

        public EmailMessage build() {
            return new EmailMessage(to, cc, bcc, replyTo, subject, htmlBody, textBody, attachments);
        }

        private Builder addAll(List<String> target, String... values) {
            target.addAll(List.of(values));
            return this;
        }
    }
}
