package com.packing.backend.domain.shared;

public class DomainRuleViolationException extends DomainException {

    public DomainRuleViolationException(String message) {
        super(message);
    }
}
