package com.packing.backend.api.file;

import com.packing.backend.core.file.FileView;

import java.time.Instant;
import java.util.UUID;

public record FileResponse(
        UUID id,
        String filename,
        String format,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String status,
        Instant createdAt) {

    public static FileResponse from(FileView view) {
        return new FileResponse(
                view.id(),
                view.filename(),
                view.format().name(),
                view.contentType(),
                view.sizeBytes(),
                view.checksumSha256(),
                view.status().name(),
                view.createdAt());
    }
}
