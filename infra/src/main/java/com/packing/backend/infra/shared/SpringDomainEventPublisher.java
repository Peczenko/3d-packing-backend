package com.packing.backend.infra.shared;

import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.shared.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Dispatches domain events through Spring's application event mechanism.
 *
 * <p>Because publication happens inside the use case's transaction, listeners can opt
 * into {@code @TransactionalEventListener} to run only after a successful commit.
 */
@Component
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher delegate;

    public SpringDomainEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publishAll(Collection<? extends DomainEvent> events) {
        events.forEach(delegate::publishEvent);
    }
}
