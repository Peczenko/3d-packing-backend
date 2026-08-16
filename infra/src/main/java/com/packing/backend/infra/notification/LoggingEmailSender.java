package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class LoggingEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        log.info("Email not sent (app.email.enabled=false). to={} subject='{}' "
                + "htmlChars={} attachments={}",
                 message.to(),
                 message.subject(),
                 message.htmlBody() == null ? 0
                                            : message.htmlBody()
                                                     .length(),
                 message.attachments()
                        .size());
    }
}
