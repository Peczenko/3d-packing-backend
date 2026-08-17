package com.packing.backend.domain.file.event;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;

import java.time.Instant;

public record FileDeleted(
        FileId fileId,
        StorageKey storageKey,
        UserId ownerId,
        Instant occurredAt) implements DomainEvent {
}
