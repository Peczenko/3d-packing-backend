package com.packing.backend.domain.user.event;

import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

/**
 * Raised once the local profile has been anonymised. The handler that removes the
 * Firebase identity reacts to this <em>after</em> the database commit, so a failure there
 * cannot roll back an account the user has already been told is gone.
 */
public record UserAccountDeleted(
        UserId userId,
        FirebaseUid firebaseUid,
        Instant occurredAt) implements DomainEvent {
}
