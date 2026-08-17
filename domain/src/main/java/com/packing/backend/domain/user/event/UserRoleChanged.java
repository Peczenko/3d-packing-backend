package com.packing.backend.domain.user.event;

import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;

import java.time.Instant;

public record UserRoleChanged(
        UserId userId,
        FirebaseUid firebaseUid,
        UserRole previousRole,
        UserRole newRole,
        Instant occurredAt) implements DomainEvent {
}
