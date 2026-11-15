package com.packing.backend.domain.file;

import java.util.Objects;

public record ConvertedObj(StorageKey storageKey, long sizeBytes, Checksum checksum) {

    public static final long MAX_SIZE_BYTES = 256L * 1024 * 1024;

    public ConvertedObj {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(checksum, "checksum");
    }

    public static ConvertedObj of(FileId fileId, long sizeBytes, Checksum checksum) {
        return new ConvertedObj(StorageKey.forConvertedObj(fileId), sizeBytes, checksum);
    }
}
