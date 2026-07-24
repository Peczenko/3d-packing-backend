package com.packing.backend.core.file;

import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;

import java.time.Instant;
import java.util.UUID;

/** Excludes {@code storageKey}: exposing it would leak the object layout to clients. */
public record FileView(
        UUID id,
        String filename,
        ModelFormat format,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        FileStatus status,
        Instant createdAt) {

    public static FileView from(StoredFile file) {
        return new FileView(
                file.id().value(),
                file.name().value(),
                file.format(),
                file.contentType(),
                file.sizeBytes(),
                file.checksum().value(),
                file.status(),
                file.createdAt());
    }
}
