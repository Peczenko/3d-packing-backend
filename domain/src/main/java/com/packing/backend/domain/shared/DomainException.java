package com.packing.backend.domain.shared;

/**
 * Base type for every exception the domain raises.
 *
 * <p>The three subtypes below exist so that adapters can translate domain failures into
 * transport semantics without knowing about individual exception classes: the REST
 * exception handler maps {@link ResourceNotFoundException} to 404,
 * {@link ResourceConflictException} to 409 and {@link DomainRuleViolationException} to
 * 422, and every future aggregate inherits that mapping for free.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
