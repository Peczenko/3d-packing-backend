package com.packing.backend.core.shared.port.out;

import com.packing.backend.domain.shared.DomainEvent;

import java.util.Collection;

public interface DomainEventPublisher {

    void publishAll(Collection<? extends DomainEvent> events);
}
