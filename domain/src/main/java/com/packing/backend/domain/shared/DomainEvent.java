package com.packing.backend.domain.shared;

import java.time.Instant;

public interface DomainEvent {

    Instant occurredAt();
}
