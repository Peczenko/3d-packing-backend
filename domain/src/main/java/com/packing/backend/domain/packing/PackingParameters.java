package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;

public record PackingParameters(String json) {

    public PackingParameters {
        if (json == null || json.isBlank()) {
            throw new DomainRuleViolationException("Packing parameters must not be blank");
        }
    }
}
