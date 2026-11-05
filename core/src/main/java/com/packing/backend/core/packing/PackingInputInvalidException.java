package com.packing.backend.core.packing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class PackingInputInvalidException extends RuntimeException {

    private final Map<String, String> errors;

    public PackingInputInvalidException(Map<String, String> errors) {
        super("Packing job input does not match the input schema: " + errors.keySet());
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public Map<String, String> errors() {
        return errors;
    }
}
