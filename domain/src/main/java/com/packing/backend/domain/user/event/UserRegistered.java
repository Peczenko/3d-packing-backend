package com.packing.backend.domain.user.event;

import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

/** A local profile was created for a Firebase identity seen for the first time. */
public record UserRegistered(
        UserId userId,
        FirebaseUid firebaseUid,
        Email email,
        Instant occurredAt) implements DomainEvent {
}
