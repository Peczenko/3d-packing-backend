package com.packing.backend.infra.packing.preview;

public class UnrecognizedPackerOutputException extends RuntimeException {

    public UnrecognizedPackerOutputException(String message) {
        super(message);
    }
}
