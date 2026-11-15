package com.packing.backend.infra.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

record ReadUrl(Duration validity, boolean aligned, String attachmentName, String contentType) {

    ReadUrl {
        Objects.requireNonNull(validity, "validity");
    }

    static ReadUrl validFor(Duration validity) {
        return new ReadUrl(validity, false, null, null);
    }

    ReadUrl cacheable() {
        return new ReadUrl(validity, true, attachmentName, contentType);
    }

    ReadUrl asAttachment(String name) {
        return new ReadUrl(validity, aligned, name, contentType);
    }

    ReadUrl servedAs(String type) {
        return new ReadUrl(validity, aligned, attachmentName, type);
    }

    SasWindow windowAt(Instant now) {
        return aligned ? SasWindow.aligned(now, validity) : SasWindow.sliding(now, validity);
    }
}
