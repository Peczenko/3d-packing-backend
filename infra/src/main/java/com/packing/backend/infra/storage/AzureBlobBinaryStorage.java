package com.packing.backend.infra.storage;

import com.azure.storage.blob.models.BlobHttpHeaders;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.domain.file.StorageKey;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AzureBlobBinaryStorage implements BinaryStorage {

    private final BlobContainer         container;
    private final BlobStorageProperties properties;

    @Override
    public void write(StorageKey key, InputStream content, String contentType) {
        container.upload(key.value(), content, new BlobHttpHeaders().setContentType(contentType));
    }

    @Override
    public TemporaryUrl readUrl(StorageKey key, String downloadFilename, String contentType) {
        return container.readUrl(key.value(),
                                 ReadUrl.validFor(properties.downloadUrlTtl())
                                        .asAttachment(downloadFilename)
                                        .servedAs(contentType));
    }

    @Override
    public void delete(StorageKey key) {
        container.deleteIfExists(key.value());
    }
}
