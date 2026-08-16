package com.packing.backend.domain.project.event;

import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

public record ProjectAccessGranted(ProjectId projectId,
        ProjectName projectName,
        UserId userId,
        ProjectPermission permission,
        UserId grantedBy,
        Instant occurredAt) implements DomainEvent {
}
