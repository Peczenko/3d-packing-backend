package com.packing.backend.domain.shared;

/** The operation conflicts with existing state, typically a uniqueness constraint. */
public class ResourceConflictException extends DomainException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
