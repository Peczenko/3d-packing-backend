package com.packing.backend.domain.file;

import java.util.Objects;

public record ModelContent(StorageKey storageKey, ModelFormat format, Checksum checksum) {

    public ModelContent {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(checksum, "checksum");
    }
}
