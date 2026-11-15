package com.packing.backend.core.file.port.out;

import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.domain.file.StorageKey;

import java.io.InputStream;

public interface BinaryStorage {

    void write(StorageKey key, InputStream content, String contentType);

    TemporaryUrl readUrl(StorageKey key, String downloadFilename, String contentType);

    void delete(StorageKey key);
}
