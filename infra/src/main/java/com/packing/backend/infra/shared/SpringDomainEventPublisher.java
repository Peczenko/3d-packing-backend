package com.packing.backend.infra.shared;

import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.shared.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
@RequiredArgsConstructor
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher delegate;

    @Override
    public void publishAll(Collection<? extends DomainEvent> events) {
        events.forEach(delegate::publishEvent);
    }
}
