package com.packing.backend.domain.shared;

/** An invariant was violated: the request was well-formed but the domain rejects it. */
public class DomainRuleViolationException extends DomainException {

    public DomainRuleViolationException(String message) {
        super(message);
    }
}
