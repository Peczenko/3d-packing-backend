package com.packing.backend.domain.user.event;

import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;

import java.time.Instant;

public record UserProfileChanged(
        UserId userId,
        Username username,
        String displayName,
        Instant occurredAt) implements DomainEvent {
}
