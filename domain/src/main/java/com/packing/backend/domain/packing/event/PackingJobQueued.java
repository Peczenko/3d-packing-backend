package com.packing.backend.domain.packing.event;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.DomainEvent;

import java.time.Instant;

public record PackingJobQueued(PackingJobId jobId,
        ProjectId projectId,
        Instant occurredAt) implements DomainEvent {
}
