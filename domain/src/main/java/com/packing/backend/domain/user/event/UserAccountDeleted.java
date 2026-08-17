package com.packing.backend.domain.user.event;

import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

public record UserAccountDeleted(
        UserId userId,
        FirebaseUid firebaseUid,
        Instant occurredAt) implements DomainEvent {
}
