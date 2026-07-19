package com.packing.backend.domain.shared;

/** A referenced aggregate does not exist. */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
