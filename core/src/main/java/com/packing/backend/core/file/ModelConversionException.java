package com.packing.backend.core.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;

public class ModelConversionException extends DomainRuleViolationException {

    public ModelConversionException(String message) {
        super(message);
    }
}
