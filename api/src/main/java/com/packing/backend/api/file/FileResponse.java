package com.packing.backend.api.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;

import java.time.Instant;
import java.util.UUID;

public record FileResponse(
        UUID id,
        UUID projectId,
        String filename,
        ModelFormat format,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        FileStatus status,
        Instant createdAt) {

    public static FileResponse from(FileView view) {
        return new FileResponse(
                                view.id(),
                                view.projectId(),
                                view.filename(),
                                view.format(),
                                view.contentType(),
                                view.sizeBytes(),
                                view.checksumSha256(),
                                view.status(),
                                view.createdAt());
    }
}
