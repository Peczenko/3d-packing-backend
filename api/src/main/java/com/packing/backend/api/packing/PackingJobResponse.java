package com.packing.backend.api.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.domain.packing.PackingJobStatus;

import java.time.Instant;
import java.util.UUID;

public record PackingJobResponse(
        UUID id,
        UUID projectId,
        PackingJobStatus status,
        String engineVersion,
        String engineChecksum,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String failureReason,
        String resultFileName,
        String resultContentType,
        Long resultSizeBytes,
        String resultChecksum,
        PackingJobInputResponse input) {

    public static PackingJobResponse from(PackingJobView view) {
        return new PackingJobResponse(
                                      view.id(),
                                      view.projectId(),
                                      view.status(),
                                      view.engineVersion(),
                                      view.engineChecksum(),
                                      view.createdAt(),
                                      view.startedAt(),
                                      view.finishedAt(),
                                      view.failureReason(),
                                      view.resultFileName(),
                                      view.resultContentType(),
                                      view.resultSizeBytes(),
                                      view.resultChecksum(),
                                      view.input() == null ? null : PackingJobInputResponse.from(view.input()));
    }
}
