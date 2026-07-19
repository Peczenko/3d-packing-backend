package com.packing.backend.core.shared.port.out;

import com.packing.backend.domain.shared.DomainEvent;

import java.util.Collection;

/**
 * Output port for handing recorded domain events to whatever dispatch mechanism the
 * runtime provides.
 *
 * <p>Keeping this behind a port is what lets application services stay free of any
 * publish/subscribe framework: they drain an aggregate's buffer after a successful save
 * and hand it here.
 */
public interface DomainEventPublisher {

    void publishAll(Collection<? extends DomainEvent> events);
}
