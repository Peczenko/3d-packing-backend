package com.packing.backend.core.notification;

import java.util.Objects;

public record EmailAttachment(String fileName, byte[] content) {

    public EmailAttachment {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Attachment file name must not be blank");
        }
        Objects.requireNonNull(content, "content");
        if (content.length == 0) {
            throw new IllegalArgumentException("Attachment '" + fileName + "' is empty");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public int sizeBytes() {
        return content.length;
    }
}
