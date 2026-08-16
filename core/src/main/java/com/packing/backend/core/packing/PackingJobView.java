package com.packing.backend.core.packing;

import com.packing.backend.domain.packing.PackingJobStatus;

import java.time.Instant;
import java.util.UUID;

public record PackingJobView(
        UUID id,
        UUID projectId,
        PackingJobStatus status,
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
}
