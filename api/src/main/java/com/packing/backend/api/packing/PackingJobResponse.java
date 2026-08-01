package com.packing.backend.api.packing;

import com.packing.backend.core.packing.PackingJobView;

import java.time.Instant;
import java.util.UUID;

public record PackingJobResponse(
        UUID id,
        UUID projectId,
        String status,
        long maxRuntimeSeconds,
        String engineVersion,
        String engineChecksum,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String failureReason,
        String resultFileName,
        String resultContentType,
        Long resultSizeBytes,
        String resultChecksum) {

    public static PackingJobResponse from(PackingJobView view) {
        return new PackingJobResponse(
                view.id(),
                view.projectId(),
                view.status().name(),
                view.maxRuntimeSeconds(),
                view.engineVersion(),
                view.engineChecksum(),
                view.createdAt(),
                view.startedAt(),
                view.finishedAt(),
                view.failureReason(),
                view.resultFileName(),
                view.resultContentType(),
                view.resultSizeBytes(),
                view.resultChecksum());
    }
}
