package com.packing.backend.core.file.port.in;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface DownloadFileUseCase {

    FileDownload prepareDownload(PrepareDownloadCommand command);

    record PrepareDownloadCommand(String firebaseUid, UUID fileId) {
    }

    /** The URL embeds a credential. It must not be logged, cached or stored. */
    record FileDownload(URI url, Instant expiresAt) {
    }
}
