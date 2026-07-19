package com.packing.backend.domain.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Consistency boundary that buffers the events raised while it is being mutated.
 *
 * <p>This is a deliberate exception to the project's "prefer composition over inheritance
 * in the domain" rule: it carries no domain behaviour at all, only the event buffer, and
 * every future aggregate needs exactly this. The application layer drains the buffer with
 * {@link #pullDomainEvents()} after a successful save.
 */
public abstract class AggregateRoot {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected void recordEvent(DomainEvent event) {
        domainEvents.add(Objects.requireNonNull(event, "event"));
    }

    /** Events recorded so far, without clearing them. */
    public List<DomainEvent> domainEvents() {
        return List.copyOf(domainEvents);
    }

    /** Returns the recorded events and empties the buffer. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }
}
