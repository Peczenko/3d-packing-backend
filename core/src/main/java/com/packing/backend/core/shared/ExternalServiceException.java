package com.packing.backend.core.shared;

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
