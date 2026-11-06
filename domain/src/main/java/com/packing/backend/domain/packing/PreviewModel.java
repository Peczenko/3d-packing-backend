package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Objects;

public record PreviewModel(PreviewQuality quality, long triangles, long sizeBytes) {

    public PreviewModel {
        Objects.requireNonNull(quality, "quality");
        if (triangles <= 0) {
            throw new DomainRuleViolationException("A preview model must have at least one triangle");
        }
        if (sizeBytes <= 0) {
            throw new DomainRuleViolationException("A preview model must have a positive size");
        }
    }
}
