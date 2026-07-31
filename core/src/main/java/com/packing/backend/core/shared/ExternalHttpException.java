package com.packing.backend.core.shared;

public final class ExternalHttpException extends ExternalServiceException {

    private final int statusCode;
    private final String method;
    private final String path;
    private final String providerCode;
    private final String retryAfter;
    private final boolean retryable;

    public ExternalHttpException(
            String service,
            int statusCode,
            String method,
            String path,
            String providerCode,
            String retryAfter,
            boolean retryable,
            String message) {
        super(service, message);
        this.statusCode = statusCode;
        this.method = method;
        this.path = path;
        this.providerCode = providerCode;
        this.retryAfter = retryAfter;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String providerCode() {
        return providerCode;
    }

    public String retryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }
}
