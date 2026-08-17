package com.packing.backend.domain.user.event;

import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

public record UserRegistered(
        UserId userId,
        FirebaseUid firebaseUid,
        Email email,
        Instant occurredAt) implements DomainEvent {
}
