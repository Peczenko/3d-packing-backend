package com.packing.backend.infra.storage;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.file.StorageKey;

import java.io.InputStream;

/**
 * Throws rather than silently no-op'ing: a file endpoint that appears to succeed while
 * storing nothing is far worse than one that reports the dependency as unavailable.
 */
class UnavailableBinaryStorage implements BinaryStorage {

    private static final String SERVICE = "azure-blob-storage";
    private static final String MESSAGE =
            "Object storage is not configured. Set app.storage.enabled=true and provide "
                    + "spring.cloud.azure.storage.blob.connection-string (or .endpoint).";

    @Override
    public void write(StorageKey key, InputStream content, long contentLength, String contentType) {
        throw new ExternalServiceException(SERVICE, MESSAGE);
    }

    @Override
    public TemporaryUrl temporaryReadUrl(StorageKey key, String downloadFilename, String contentType) {
        throw new ExternalServiceException(SERVICE, MESSAGE);
    }

    @Override
    public void delete(StorageKey key) {
        throw new ExternalServiceException(SERVICE, MESSAGE);
    }
}
