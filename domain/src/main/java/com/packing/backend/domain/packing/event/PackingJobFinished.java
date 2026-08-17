package com.packing.backend.domain.packing.event;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

public record PackingJobFinished(PackingJobId jobId,
        ProjectId projectId,
        UserId requestedBy,
        PackingJobStatus status,
        String failureReason,
        String resultFileName,
        Long resultSizeBytes,
        Instant startedAt,
        Instant occurredAt) implements DomainEvent {
}
