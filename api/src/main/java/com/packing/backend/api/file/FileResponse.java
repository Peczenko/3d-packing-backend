package com.packing.backend.api.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record FileResponse(
        UUID id,
        UUID projectId,
        String filename,
        ModelFormat format,
        @Schema(description = "Format the packing engine receives for this file: OBJ for an OBJ upload or a converted model, "
                + "null for a file uploaded before conversion existed", nullable = true) ModelFormat packingFormat,
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
                                view.packingFormat(),
                                view.contentType(),
                                view.sizeBytes(),
                                view.checksumSha256(),
                                view.status(),
                                view.createdAt());
    }
}
