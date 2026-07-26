package com.packing.backend.core.notification.port.out;

import com.packing.backend.core.notification.EmailMessage;

public interface EmailSender {

    void send(EmailMessage message);
}
