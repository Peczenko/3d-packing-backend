package com.packing.backend.core.shared;

/**
 * A driven adapter could not complete a call to a third-party system.
 *
 * <p>Distinct from {@link com.packing.backend.domain.shared.DomainException}: nothing is
 * wrong with the request or with domain state, the dependency is simply unavailable or
 * failed. The REST layer maps this to 503 rather than to a 4xx.
 */
public class ExternalServiceException extends RuntimeException {

    private final String service;

    public ExternalServiceException(String service, String message) {
        super(message);
        this.service = service;
    }

    public ExternalServiceException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
    }

    public String service() {
        return service;
    }
}
