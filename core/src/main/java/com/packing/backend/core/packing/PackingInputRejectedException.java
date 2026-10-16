package com.packing.backend.core.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class PackingInputRejectedException extends DomainRuleViolationException {

    private final Map<String, String> errors;

    public PackingInputRejectedException(Map<String, String> errors) {
        super("One or more items cannot be packed");
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public Map<String, String> errors() {
        return errors;
    }
}
