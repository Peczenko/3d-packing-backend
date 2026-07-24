package com.packing.backend.domain.file.event;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

/**
 * Carries the {@link StorageKey} so the after-commit listener that removes the blob does
 * not have to read the row back to find it.
 */
public record FileDeleted(
        FileId fileId,
        StorageKey storageKey,
        UserId ownerId,
        Instant occurredAt) implements DomainEvent {
}
