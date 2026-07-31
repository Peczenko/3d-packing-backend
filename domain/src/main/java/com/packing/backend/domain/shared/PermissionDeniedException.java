package com.packing.backend.domain.shared;

public class PermissionDeniedException extends DomainException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
