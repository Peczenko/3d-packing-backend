package com.packing.backend.core.file;

import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelContent;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;

import java.time.Instant;
import java.util.UUID;

public record FileView(
        UUID id,
        UUID projectId,
        String filename,
        ModelFormat format,
        ModelFormat packingFormat,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        FileStatus status,
        Instant createdAt) {

    public static FileView from(StoredFile file) {
        return new FileView(
                            file.id()
                                .value(),
                            file.projectId()
                                .value(),
                            file.name()
                                .value(),
                            file.format(),
                            file.packingContent()
                                .map(ModelContent::format)
                                .orElse(null),
                            file.contentType(),
                            file.sizeBytes(),
                            file.checksum()
                                .value(),
                            file.status(),
                            file.createdAt());
    }
}
