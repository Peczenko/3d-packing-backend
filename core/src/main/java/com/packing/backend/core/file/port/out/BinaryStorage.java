package com.packing.backend.core.file.port.out;

import com.packing.backend.domain.file.StorageKey;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

public interface BinaryStorage {

    void write(StorageKey key, InputStream content, long contentLength, String contentType);

    TemporaryUrl temporaryReadUrl(StorageKey key, String downloadFilename, String contentType);

    void delete(StorageKey key);

    /** The URL embeds a credential. It must not be logged, cached or persisted. */
    record TemporaryUrl(URI url, Instant expiresAt) {
    }
}
